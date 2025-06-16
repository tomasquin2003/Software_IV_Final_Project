package com.registraduria.votacion.regional.cache;

import Votacion.*;
import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * CacheRegionalImpl - Implementación del caché regional con persistencia.
 * 
 * Características:
 * - Caché en memoria con respaldo en SQLite
 * - TTL configurable para entradas
 * - Limpieza automática de entradas expiradas
 * - Métricas de rendimiento
 * - Thread-safe con locks de lectura/escritura
 */
public class CacheRegionalImpl implements CacheRegional {
    private static final Logger logger = LoggerFactory.getLogger(CacheRegionalImpl.class);
    
    // Configuración
    private final String dataDir;
    private final String regionId;
    private final int maxCacheSize;
    private final int defaultTTL;
    
    // Base de datos
    private Connection dbConnection;
    private final String dbPath;
    
    // Caché en memoria para acceso rápido
    private final ConcurrentHashMap<String, CacheEntry> memoryCache;
    private final ReadWriteLock cacheLock;
    
    // Métricas
    private long consultasExitosas = 0;
    private long fallosCache = 0;
    private long actualizaciones = 0;
    private long limpiezas = 0;
    
    // Servicios auxiliares
    private final ScheduledExecutorService maintenanceExecutor;
    
    /**
     * Entrada del caché en memoria.
     */
    private static class CacheEntry {
        DatosCache datos;
        long timestampCreacion;
        long timestampAcceso;
        boolean enBaseDatos;
        
        CacheEntry(DatosCache datos) {
            this.datos = datos;
            this.timestampCreacion = System.currentTimeMillis();
            this.timestampAcceso = timestampCreacion;
            this.enBaseDatos = false;
        }
        
        boolean estaExpirado() {
            long tiempoVida = System.currentTimeMillis() - timestampCreacion;
            return tiempoVida > (datos.ttl * 1000L);
        }
        
        void marcarAcceso() {
            this.timestampAcceso = System.currentTimeMillis();
        }
    }
    
    /**
     * Constructor del CacheRegional.
     */
    public CacheRegionalImpl(String dataDir, String regionId, int maxCacheSize, int defaultTTL) {
        this.dataDir = dataDir;
        this.regionId = regionId;
        this.maxCacheSize = maxCacheSize;
        this.defaultTTL = defaultTTL;
        this.dbPath = dataDir + "/cache/RegionalCache_" + regionId + ".db";
        
        this.memoryCache = new ConcurrentHashMap<>();
        this.cacheLock = new ReentrantReadWriteLock();
        this.maintenanceExecutor = Executors.newScheduledThreadPool(2);
        
        inicializarBaseDatos();
        cargarCacheDesdeBaseDatos();
        configurarMantenimiento();
        
        logger.info("CacheRegional inicializado - Región: {}, MaxSize: {}, TTL: {}s", 
                   regionId, maxCacheSize, defaultTTL);
    }
    
    /**
     * Inicializa la base de datos SQLite.
     */
    private void inicializarBaseDatos() {
        try {
            // Crear directorio si no existe
            Files.createDirectories(Paths.get(dataDir + "/cache"));
            
            // Conectar a SQLite
            String url = "jdbc:sqlite:" + dbPath;
            dbConnection = DriverManager.getConnection(url);
            
            // Crear tabla si no existe
            String createTableSQL = "CREATE TABLE IF NOT EXISTS cache_entries (" +
                "clave TEXT PRIMARY KEY," +
                "valor TEXT NOT NULL," +
                "timestamp TEXT NOT NULL," +
                "ttl INTEGER NOT NULL," +
                "region TEXT NOT NULL," +
                "valido BOOLEAN NOT NULL," +
                "timestamp_creacion INTEGER NOT NULL," +
                "timestamp_acceso INTEGER NOT NULL" +
                ")";
            
            try (Statement stmt = dbConnection.createStatement()) {
                stmt.execute(createTableSQL);
                
                // Crear índices para mejorar rendimiento
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_region ON cache_entries(region)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_timestamp ON cache_entries(timestamp_creacion)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_valido ON cache_entries(valido)");
            }
            
            logger.info("Base de datos de caché inicializada: {}", dbPath);
            
        } catch (Exception e) {
            logger.error("Error inicializando base de datos: {}", e.getMessage());
            throw new RuntimeException("Error crítico inicializando caché", e);
        }
    }
    
    /**
     * Carga entradas válidas desde la base de datos al iniciar.
     */
    private void cargarCacheDesdeBaseDatos() {
        try {
            String selectSQL = "SELECT clave, valor, timestamp, ttl, region, valido, " +
                "timestamp_creacion, timestamp_acceso " +
                "FROM cache_entries " +
                "WHERE region = ? AND valido = 1 " +
                "ORDER BY timestamp_acceso DESC " +
                "LIMIT ?";
            
            try (PreparedStatement pstmt = dbConnection.prepareStatement(selectSQL)) {
                pstmt.setString(1, regionId);
                pstmt.setInt(2, maxCacheSize);
                
                ResultSet rs = pstmt.executeQuery();
                int cargadas = 0;
                
                while (rs.next()) {
                    DatosCache datos = new DatosCache();
                    datos.clave = rs.getString("clave");
                    datos.valor = rs.getString("valor");
                    datos.timestamp = rs.getString("timestamp");
                    datos.ttl = rs.getInt("ttl");
                    datos.region = rs.getString("region");
                    datos.valido = rs.getBoolean("valido");
                    
                    CacheEntry entry = new CacheEntry(datos);
                    entry.timestampCreacion = rs.getLong("timestamp_creacion");
                    entry.timestampAcceso = rs.getLong("timestamp_acceso");
                    entry.enBaseDatos = true;
                    
                    // Solo cargar si no está expirado
                    if (!entry.estaExpirado()) {
                        memoryCache.put(datos.clave, entry);
                        cargadas++;
                    }
                }
                
                logger.info("Cargadas {} entradas válidas desde base de datos", cargadas);
            }
            
        } catch (Exception e) {
            logger.error("Error cargando caché desde base de datos: {}", e.getMessage());
        }
    }
    
    /**
     * Configura tareas de mantenimiento automático.
     */
    private void configurarMantenimiento() {
        // Limpieza de entradas expiradas cada 15 minutos
        maintenanceExecutor.scheduleAtFixedRate(
            this::limpiarExpiradas,
            15, 15, TimeUnit.MINUTES
        );
        
        // Sincronización con base de datos cada 5 minutos
        maintenanceExecutor.scheduleAtFixedRate(
            this::sincronizarBaseDatos,
            5, 5, TimeUnit.MINUTES
        );
        
        logger.info("Tareas de mantenimiento configuradas");
    }
    
    @Override
    public DatosCache obtenerDatosCache(String cedula, Current current) throws CacheException {
        if (cedula == null || cedula.trim().isEmpty()) {
            throw new CacheException("obtenerDatosCache", "Clave no puede estar vacía");
        }
        
        cedula = cedula.trim();
        
        cacheLock.readLock().lock();
        try {
            CacheEntry entry = memoryCache.get(cedula);
            
            if (entry != null) {
                if (!entry.estaExpirado()) {
                    entry.marcarAcceso();
                    consultasExitosas++;
                    logger.debug("Cache hit para cédula: {}", cedula);
                    return entry.datos;
                } else {
                    // Entrada expirada, eliminar
                    cacheLock.readLock().unlock();
                    cacheLock.writeLock().lock();
                    try {
                        memoryCache.remove(cedula);
                        eliminarDeBaseDatos(cedula);
                    } finally {
                        cacheLock.readLock().lock();
                        cacheLock.writeLock().unlock();
                    }
                }
            }
            
            // No encontrado o expirado
            fallosCache++;
            logger.debug("Cache miss para cédula: {}", cedula);
            return null;
            
        } catch (Exception e) {
            fallosCache++;
            logger.error("Error obteniendo datos de caché para {}: {}", cedula, e.getMessage());
            throw new CacheException("obtenerDatosCache", "Error accediendo al caché: " + e.getMessage());
        } finally {
            cacheLock.readLock().unlock();
        }
    }
    
    @Override
    public void actualizarCache(DatosCache datos, Current current) throws CacheException {
        if (datos == null || datos.clave == null || datos.clave.trim().isEmpty()) {
            throw new CacheException("actualizarCache", "Datos o clave no válidos");
        }
        
        datos.clave = datos.clave.trim();
        
        // Validar datos
        if (datos.valor == null) datos.valor = "";
        if (datos.region == null) datos.region = regionId;
        if (datos.ttl <= 0) datos.ttl = defaultTTL;
        if (datos.timestamp == null) {
            datos.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        datos.valido = true;
        
        cacheLock.writeLock().lock();
        try {
            // Verificar límite de tamaño
            if (memoryCache.size() >= maxCacheSize && !memoryCache.containsKey(datos.clave)) {
                eliminarEntradaMasAntigua();
            }
            
            // Crear entrada
            CacheEntry entry = new CacheEntry(datos);
            memoryCache.put(datos.clave, entry);
            
            // Guardar en base de datos de forma asíncrona
            maintenanceExecutor.submit(() -> guardarEnBaseDatos(entry));
            
            actualizaciones++;
            logger.debug("Caché actualizado para cédula: {}", datos.clave);
            
        } catch (Exception e) {
            logger.error("Error actualizando caché para {}: {}", datos.clave, e.getMessage());
            throw new CacheException("actualizarCache", "Error actualizando caché: " + e.getMessage());
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    @Override
    public void limpiarCache(Current current) throws CacheException, ErrorPersistenciaException {
        cacheLock.writeLock().lock();
        try {
            memoryCache.clear();
            
            // Limpiar base de datos
            String deleteSQL = "DELETE FROM cache_entries WHERE region = ?";
            try (PreparedStatement pstmt = dbConnection.prepareStatement(deleteSQL)) {
                pstmt.setString(1, regionId);
                int eliminadas = pstmt.executeUpdate();
                
                limpiezas++;
                logger.info("Caché limpiado completamente - {} entradas eliminadas", eliminadas);
            }
            
        } catch (Exception e) {
            logger.error("Error limpiando caché: {}", e.getMessage());
            throw new CacheException("limpiarCache", "Error limpiando caché: " + e.getMessage());
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    @Override
    public EstadoCacheInfo verificarEstadoCache(Current current) throws CacheException {
        cacheLock.readLock().lock();
        try {
            EstadoCacheInfo estado = new EstadoCacheInfo();
            
            estado.totalEntradas = memoryCache.size();
            estado.entradasValidas = (int) memoryCache.values().stream()
                .filter(entry -> !entry.estaExpirado())
                .count();
            estado.consultasExitosas = (int) consultasExitosas;
            estado.fallosCache = (int) fallosCache;
            estado.ultimaActualizacion = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            estado.estado = determinarEstadoCache();
            
            return estado;
            
        } catch (Exception e) {
            logger.error("Error verificando estado del caché: {}", e.getMessage());
            throw new CacheException("verificarEstadoCache", "Error verificando estado: " + e.getMessage());
        } finally {
            cacheLock.readLock().unlock();
        }
    }
    
    /**
     * Limpia solo las entradas expiradas (método público).
     */
    public void limpiarEntradas() {
        limpiarExpiradas();
    }
    
    /**
     * Elimina la entrada más antigua para hacer espacio.
     */
    private void eliminarEntradaMasAntigua() {
        String claveEliminacion = memoryCache.entrySet().stream()
            .min((e1, e2) -> Long.compare(e1.getValue().timestampAcceso, e2.getValue().timestampAcceso))
            .map(entry -> entry.getKey())
            .orElse(null);
        
        if (claveEliminacion != null) {
            memoryCache.remove(claveEliminacion);
            eliminarDeBaseDatos(claveEliminacion);
            logger.debug("Eliminada entrada antigua: {}", claveEliminacion);
        }
    }
    
    /**
     * Limpia entradas expiradas del caché.
     */
    private void limpiarExpiradas() {
        cacheLock.writeLock().lock();
        try {
            var entradasExpiradas = memoryCache.entrySet().stream()
                .filter(entry -> entry.getValue().estaExpirado())
                .map(entry -> entry.getKey())
                .collect(Collectors.toList());
            
            for (String clave : entradasExpiradas) {
                memoryCache.remove(clave);
                eliminarDeBaseDatos(clave);
            }
            
            if (!entradasExpiradas.isEmpty()) {
                logger.info("Limpiadas {} entradas expiradas", entradasExpiradas.size());
            }
            
        } catch (Exception e) {
            logger.error("Error en limpieza automática: {}", e.getMessage());
        } finally {
            cacheLock.writeLock().unlock();
        }
    }
    
    /**
     * Sincroniza caché en memoria con base de datos.
     */
    private void sincronizarBaseDatos() {
        try {
            // Guardar entradas no persistidas
            for (CacheEntry entry : memoryCache.values()) {
                if (!entry.enBaseDatos && !entry.estaExpirado()) {
                    guardarEnBaseDatos(entry);
                }
            }
            
            // Limpiar entradas expiradas de la base de datos
            String deleteExpiredSQL = "DELETE FROM cache_entries " +
                "WHERE region = ? AND (timestamp_creacion + (ttl * 1000)) < ?";
            
            try (PreparedStatement pstmt = dbConnection.prepareStatement(deleteExpiredSQL)) {
                pstmt.setString(1, regionId);
                pstmt.setLong(2, System.currentTimeMillis());
                int eliminadas = pstmt.executeUpdate();
                
                if (eliminadas > 0) {
                    logger.debug("Eliminadas {} entradas expiradas de la base de datos", eliminadas);
                }
            }
            
        } catch (Exception e) {
            logger.error("Error en sincronización con base de datos: {}", e.getMessage());
        }
    }
    
    /**
     * Guarda una entrada en la base de datos.
     */
    private void guardarEnBaseDatos(CacheEntry entry) {
        try {
            String insertSQL = "INSERT OR REPLACE INTO cache_entries " +
                "(clave, valor, timestamp, ttl, region, valido, timestamp_creacion, timestamp_acceso) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            
            try (PreparedStatement pstmt = dbConnection.prepareStatement(insertSQL)) {
                pstmt.setString(1, entry.datos.clave);
                pstmt.setString(2, entry.datos.valor);
                pstmt.setString(3, entry.datos.timestamp);
                pstmt.setInt(4, entry.datos.ttl);
                pstmt.setString(5, entry.datos.region);
                pstmt.setBoolean(6, entry.datos.valido);
                pstmt.setLong(7, entry.timestampCreacion);
                pstmt.setLong(8, entry.timestampAcceso);
                
                pstmt.executeUpdate();
                entry.enBaseDatos = true;
            }
            
        } catch (Exception e) {
            logger.error("Error guardando en base de datos: {}", e.getMessage());
        }
    }
    
    /**
     * Elimina una entrada de la base de datos.
     */
    private void eliminarDeBaseDatos(String clave) {
        try {
            String deleteSQL = "DELETE FROM cache_entries WHERE clave = ? AND region = ?";
            try (PreparedStatement pstmt = dbConnection.prepareStatement(deleteSQL)) {
                pstmt.setString(1, clave);
                pstmt.setString(2, regionId);
                pstmt.executeUpdate();
            }
        } catch (Exception e) {
            logger.error("Error eliminando de base de datos: {}", e.getMessage());
        }
    }
    
    /**
     * Determina el estado actual del caché.
     */
    private EstadoCache determinarEstadoCache() {
        double hitRate = consultasExitosas + fallosCache > 0 ? 
            (double) consultasExitosas / (consultasExitosas + fallosCache) : 0;
        
        if (hitRate >= 0.8) {
            return EstadoCache.ACTIVO;
        } else if (hitRate >= 0.5) {
            return EstadoCache.SINCRONIZANDO;
        } else {
            return EstadoCache.INACTIVO;
        }
    }
    
    /**
     * Obtiene estadísticas detalladas del caché.
     */
    public String obtenerEstadisticasDetalladas() {
        cacheLock.readLock().lock();
        try {
            return String.format(
                "Cache Regional %s - Entradas: %d, Consultas exitosas: %d, Fallos: %d, " +
                "Actualizaciones: %d, Limpiezas: %d, Tasa aciertos: %.2f%%",
                regionId, memoryCache.size(), consultasExitosas, fallosCache,
                actualizaciones, limpiezas,
                consultasExitosas + fallosCache > 0 ? 
                    (double) consultasExitosas / (consultasExitosas + fallosCache) * 100 : 0
            );
        } finally {
            cacheLock.readLock().unlock();
        }
    }
    
    /**
     * Optimiza la base de datos.
     */
    public void optimizarBaseDatos() {
        try {
            try (Statement stmt = dbConnection.createStatement()) {
                stmt.execute("VACUUM");
                stmt.execute("ANALYZE");
            }
            logger.info("Base de datos optimizada");
        } catch (Exception e) {
            logger.error("Error optimizando base de datos: {}", e.getMessage());
        }
    }
    
    /**
     * Cierre ordenado del caché.
     */
    public void cerrar() {
        logger.info("Cerrando CacheRegional...");
        
        try {
            // Detener mantenimiento
            if (maintenanceExecutor != null && !maintenanceExecutor.isShutdown()) {
                maintenanceExecutor.shutdown();
                if (!maintenanceExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    maintenanceExecutor.shutdownNow();
                }
            }
            
            // Sincronización final
            sincronizarBaseDatos();
            
            // Cerrar base de datos
            if (dbConnection != null && !dbConnection.isClosed()) {
                dbConnection.close();
            }
            
            logger.info("OK CacheRegional cerrado exitosamente");
            
        } catch (Exception e) {
            logger.error("Error cerrando CacheRegional: {}", e.getMessage());
        }
    }
} 