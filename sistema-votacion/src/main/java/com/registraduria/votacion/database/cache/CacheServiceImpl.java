package com.registraduria.votacion.database.cache;

import Votacion.CacheException;
import Votacion.CacheService;
import Votacion.TipoCache;

import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CacheService - Gestión de cache de consultas frecuentes y resultados parciales.
 * 
 * Implementa políticas de TTL, invalidación por patrones y persistencia opcional.
 */
public class CacheServiceImpl implements CacheService {
    private static final Logger logger = LoggerFactory.getLogger(CacheServiceImpl.class);
    
    // Estructura para entradas de cache
    private static class CacheEntry {
        String valor;
        long timestamp;
        int ttl;
        TipoCache tipo;
        
        CacheEntry(String valor, int ttl, TipoCache tipo) {
            this.valor = valor;
            this.timestamp = System.currentTimeMillis();
            this.ttl = ttl;
            this.tipo = tipo;
        }
        
        boolean isExpired() {
            if (ttl <= 0) return false; // TTL infinito
            return (System.currentTimeMillis() - timestamp) > (ttl * 1000L);
        }
    }
    
    // Cache en memoria
    private final ConcurrentHashMap<String, CacheEntry> cache;
    
    // Archivo de persistencia
    private final String archivoPersistencia;
    private final String archivoAuditoria;
    
    // Executor para limpieza automática
    private final ScheduledExecutorService cleanupExecutor;
    
    // Métricas
    private volatile AtomicLong totalOperaciones = new AtomicLong(0);
    private volatile AtomicLong cacheHits = new AtomicLong(0);
    private volatile AtomicLong cacheMisses = new AtomicLong(0);
    private volatile AtomicLong invalidaciones = new AtomicLong(0);
    
    public CacheServiceImpl(String dataDir) {
        this.cache = new ConcurrentHashMap<>();
        this.archivoPersistencia = dataDir + "/QueryCache.db";
        this.archivoAuditoria = dataDir + "/CacheAuditoria.log";
        this.cleanupExecutor = Executors.newScheduledThreadPool(1);
        
        logger.info("CacheService inicializado");
        logger.info("Archivo persistencia: {}", archivoPersistencia);
        
        inicializarArchivos();
        cargarCachePersistente();
        programarLimpiezaAutomatica();
    }
    
    @Override
    public String getCachedResult(String key, Current current) throws CacheException {
        logger.debug("=== GET CACHED RESULT ===");
        logger.debug("Key: {}", key);
        
        try {
            totalOperaciones.incrementAndGet();
            
            if (key == null || key.trim().isEmpty()) {
                throw new CacheException(key, "Key no puede ser nula o vacía");
            }
            
            CacheEntry entry = cache.get(key);
            
            if (entry == null) {
                cacheMisses.incrementAndGet();
                logger.debug("Cache miss para key: {}", key);
                return null;
            }
            
            if (entry.isExpired()) {
                cache.remove(key);
                cacheMisses.incrementAndGet();
                escribirAuditoria("CACHE_EXPIRED", key, String.format("TTL: %d", entry.ttl));
                logger.debug("Cache expirado para key: {}", key);
                return null;
            }
            
            cacheHits.incrementAndGet();
            escribirAuditoria("CACHE_HIT", key, String.format("Tipo: %s", entry.tipo));
            logger.debug("Cache hit para key: {}", key);
            return entry.valor;
            
        } catch (Exception e) {
            logger.error("Error obteniendo desde cache key {}: {}", key, e.getMessage());
            throw new CacheException(key, "Error obteniendo desde cache: " + e.getMessage());
        }
    }
    
    @Override
    public void setCachedResult(String key, String value, int ttl, Current current) throws CacheException {
        logger.debug("=== SET CACHED RESULT ===");
        logger.debug("Key: {}, TTL: {}", key, ttl);
        
        try {
            totalOperaciones.incrementAndGet();
            
            if (key == null || key.trim().isEmpty()) {
                throw new CacheException(key, "Key no puede ser nula o vacía");
            }
            
            if (value == null) {
                throw new CacheException(key, "Value no puede ser nulo");
            }
            
            // Determinar tipo de cache
            TipoCache tipo = determinarTipoCache(key, ttl);
            
            // Crear entrada
            CacheEntry entry = new CacheEntry(value, ttl, tipo);
            cache.put(key, entry);
            
            // Persistir si es necesario
            if (tipo == TipoCache.PERSISTENTE) {
                persistirEntrada(key, entry);
            }
            
            escribirAuditoria("CACHE_SET", key, 
                String.format("TTL: %d, Tipo: %s, Bytes: %d", ttl, tipo, value.length()));
            
            logger.debug("Cache set para key: {} (TTL: {}s)", key, ttl);
            
        } catch (Exception e) {
            logger.error("Error guardando en cache key {}: {}", key, e.getMessage());
            throw new CacheException(key, "Error guardando en cache: " + e.getMessage());
        }
    }
    
    @Override
    public void invalidateCache(String pattern, Current current) throws CacheException {
        logger.info("=== INVALIDATE CACHE ===");
        logger.info("Pattern: {}", pattern);
        
        try {
            totalOperaciones.incrementAndGet();
            
            if (pattern == null || pattern.trim().isEmpty()) {
                throw new CacheException(pattern, "Pattern no puede ser nulo o vacío");
            }
            
            int invalidados = 0;
            
            // Convertir pattern simple (* como wildcard)
            String regex = pattern.replace("*", ".*");
            
            // Invalidar entradas que coincidan
            cache.entrySet().removeIf(entry -> {
                if (entry.getKey().matches(regex)) {
                    return true;
                }
                return false;
            });
            
            // Contar invalidados
            for (String key : cache.keySet()) {
                if (key.matches(regex)) {
                    invalidados++;
                }
            }
            
            invalidaciones.addAndGet(invalidados);
            
            escribirAuditoria("CACHE_INVALIDATE", pattern, 
                String.format("Invalidados: %d", invalidados));
            
            logger.info("Cache invalidado - Pattern: {}, Entradas: {}", pattern, invalidados);
            
        } catch (Exception e) {
            logger.error("Error invalidando cache pattern {}: {}", pattern, e.getMessage());
            throw new CacheException(pattern, "Error invalidando cache: " + e.getMessage());
        }
    }
    
    // === MÉTODOS PRIVADOS ===
    
    private void inicializarArchivos() {
        try {
            Path persistenciaPath = Paths.get(archivoPersistencia);
            Path auditoriaPath = Paths.get(archivoAuditoria);
            
            Files.createDirectories(persistenciaPath.getParent());
            Files.createDirectories(auditoriaPath.getParent());
            
            if (!Files.exists(persistenciaPath)) {
                Files.createFile(persistenciaPath);
                logger.info("Archivo de persistencia creado: {}", archivoPersistencia);
            }
            
            if (!Files.exists(auditoriaPath)) {
                Files.createFile(auditoriaPath);
                logger.info("Archivo de auditoría creado: {}", archivoAuditoria);
            }
            
            escribirAuditoria("INIT", "SISTEMA", "CacheService inicializado");
            
        } catch (Exception e) {
            logger.error("Error inicializando archivos: {}", e.getMessage());
        }
    }
    
    private TipoCache determinarTipoCache(String key, int ttl) {
        if (key.startsWith("candidatos_") || key.contains("config_")) {
            return TipoCache.PERSISTENTE;
        } else if (ttl > 0 && ttl <= 60) {
            return TipoCache.TEMPORAL;
        } else {
            return TipoCache.INVALIDABLE;
        }
    }
    
    private void cargarCachePersistente() {
        try {
            Path persistenciaPath = Paths.get(archivoPersistencia);
            
            if (Files.exists(persistenciaPath) && Files.size(persistenciaPath) > 0) {
                Files.lines(persistenciaPath).forEach(linea -> {
                    try {
                        if (!linea.trim().isEmpty()) {
                            String[] partes = linea.split("\\|", 5);
                            if (partes.length >= 5) {
                                String key = partes[0];
                                String valor = partes[1];
                                long timestamp = Long.parseLong(partes[2]);
                                int ttl = Integer.parseInt(partes[3]);
                                TipoCache tipo = TipoCache.valueOf(partes[4]);
                                
                                CacheEntry entry = new CacheEntry(valor, ttl, tipo);
                                entry.timestamp = timestamp;
                                
                                if (!entry.isExpired()) {
                                    cache.put(key, entry);
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Error cargando línea de cache: {}", linea);
                    }
                });
                
                logger.info("Cache persistente cargado: {} entradas", cache.size());
            }
            
        } catch (Exception e) {
            logger.error("Error cargando cache persistente: {}", e.getMessage());
        }
    }
    
    private void persistirEntrada(String key, CacheEntry entry) {
        try {
            String linea = String.format("%s|%s|%d|%d|%s%n", 
                key, entry.valor, entry.timestamp, entry.ttl, entry.tipo);
            
            Files.write(Paths.get(archivoPersistencia), linea.getBytes(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            
        } catch (Exception e) {
            logger.error("Error persistiendo entrada de cache: {}", e.getMessage());
        }
    }
    
    private void programarLimpiezaAutomatica() {
        cleanupExecutor.scheduleAtFixedRate(() -> {
            try {
                limpiarCacheExpirado();
            } catch (Exception e) {
                logger.error("Error en limpieza automática de cache: {}", e.getMessage());
            }
        }, 30, 30, TimeUnit.SECONDS);
    }
    
    private void limpiarCacheExpirado() {
        int removidas = 0;
        
        cache.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired()) {
                return true;
            }
            return false;
        });
        
        if (removidas > 0) {
            logger.debug("Limpieza automática: {} entradas expiradas removidas", removidas);
            escribirAuditoria("CLEANUP", "AUTO", String.format("Removidas: %d", removidas));
        }
    }
    
    private void escribirAuditoria(String operacion, String key, String detalles) {
        try {
            String logEntry = String.format("CACHE|%s|%s|%s|%s%n", 
                Instant.now().toString(), operacion, key, detalles);
            
            Files.write(Paths.get(archivoAuditoria), logEntry.getBytes(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            
        } catch (Exception e) {
            logger.error("Error escribiendo auditoría de cache: {}", e.getMessage());
        }
    }
    
    public String obtenerEstadisticas() {
        double hitRate = totalOperaciones.get() > 0 ? 
            (cacheHits.get() * 100.0) / totalOperaciones.get() : 0.0;
        
        return String.format(
            "CacheService - Operaciones: %d, Hits: %d (%.1f%%), Misses: %d, Entradas: %d",
            totalOperaciones.get(), cacheHits.get(), hitRate, 
            cacheMisses.get(), cache.size()
        );
    }
    
    public void shutdown() {
        logger.info("Finalizando CacheService...");
        
        if (cleanupExecutor != null && !cleanupExecutor.isShutdown()) {
            cleanupExecutor.shutdown();
        }
        
        escribirAuditoria("SHUTDOWN", "SISTEMA", obtenerEstadisticas());
        
        logger.info("CacheService finalizado");
        logger.info("Estadísticas finales: {}", obtenerEstadisticas());
    }
} 