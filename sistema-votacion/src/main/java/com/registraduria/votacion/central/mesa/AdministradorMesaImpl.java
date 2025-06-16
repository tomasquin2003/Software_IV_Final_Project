package com.registraduria.votacion.central.mesa;

import Votacion.AdministradorMesa;
import Votacion.ErrorPersistenciaException;
import Votacion.EstadoMesa;
import Votacion.MesaNoExisteException;
import Votacion.TipoMesa;

import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * AdministradorMesa - Gestiona configuración, asignación y visualización de mesas electorales.
 * 
 * Responsable de la administración completa de mesas electorales, incluyendo
 * configuración de capacidades, asignación de votantes y distribución.
 */
public class AdministradorMesaImpl implements AdministradorMesa {
    private static final Logger logger = LoggerFactory.getLogger(AdministradorMesaImpl.class);
    
    // Cache de mesas y asignaciones
    private final ConcurrentHashMap<String, MesaInfo> mesas;
    private final ConcurrentHashMap<String, AsignacionInfo> asignaciones;
    
    // Archivos de persistencia
    private final String archivoMesas;
    private final String archivoAsignaciones;
    private final String archivoAuditoria;
    
    // Locks para operaciones transaccionales
    private final ReentrantReadWriteLock lockMesas = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock lockAsignaciones = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock lockAuditoria = new ReentrantReadWriteLock();
    
    // Métricas
    private volatile AtomicLong totalMesas = new AtomicLong(0);
    private volatile AtomicLong totalAsignaciones = new AtomicLong(0);
    private volatile AtomicLong totalConsultas = new AtomicLong(0);
    
    /**
     * Constructor del AdministradorMesa.
     */
    public AdministradorMesaImpl(String dataDir) {
        this.mesas = new ConcurrentHashMap<>();
        this.asignaciones = new ConcurrentHashMap<>();
        this.archivoMesas = dataDir + "/Mesas.db";
        this.archivoAsignaciones = dataDir + "/AsignacionesMesas.db";
        this.archivoAuditoria = dataDir + "/AuditoriaMesas.log";
        
        logger.info("AdministradorMesa inicializado");
        logger.info("Archivo mesas: {}", archivoMesas);
        logger.info("Archivo asignaciones: {}", archivoAsignaciones);
        
        // Inicializar archivos
        inicializarArchivos();
        
        // Cargar datos existentes
        cargarDatosExistentes();
        
        // Crear mesas de ejemplo si no existen
        if (mesas.isEmpty()) {
            crearMesasEjemplo();
        }
    }
    
    /**
     * Consulta las asignaciones de mesas electorales.
     * 
     * @param current Contexto de Ice
     * @return String con información de asignaciones
     * @throws MesaNoExisteException Si no existen mesas configuradas
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public String consultarAsignacionMesas(Current current) 
            throws MesaNoExisteException, ErrorPersistenciaException {
        
        logger.info("=== CONSULTAR ASIGNACION MESAS ===");
        
        lockAsignaciones.readLock().lock();
        try {
            totalConsultas.incrementAndGet();
            
            if (mesas.isEmpty()) {
                throw new MesaNoExisteException("NINGUNA", "No hay mesas configuradas en el sistema");
            }
            
            StringBuilder resultado = new StringBuilder();
            resultado.append("=== ASIGNACIONES DE MESAS ELECTORALES ===\n\n");
            
            // Estadísticas generales
            resultado.append(String.format("Total mesas configuradas: %d\n", mesas.size()));
            resultado.append(String.format("Total asignaciones realizadas: %d\n\n", asignaciones.size()));
            
            // Detalles por mesa
            for (MesaInfo mesa : mesas.values()) {
                resultado.append(String.format("MESA %s:\n", mesa.mesaId));
                resultado.append(String.format("  - Ubicación: %s\n", mesa.ubicacion));
                resultado.append(String.format("  - Tipo: %s\n", mesa.tipo));
                resultado.append(String.format("  - Capacidad: %d votantes\n", mesa.capacidadMaxima));
                resultado.append(String.format("  - Estado: %s\n", mesa.estado));
                resultado.append(String.format("  - Responsable: %s\n", mesa.responsable));
                
                // Contar asignaciones de esta mesa
                long asignacionesMesa = asignaciones.values().stream()
                    .filter(asign -> asign.mesaId.equals(mesa.mesaId) && asign.confirmada)
                    .count();
                
                resultado.append(String.format("  - Asignados: %d/%d (%.1f%%)\n", 
                    asignacionesMesa, mesa.capacidadMaxima,
                    (asignacionesMesa * 100.0) / mesa.capacidadMaxima));
                resultado.append("\n");
            }
            
            // Escribir auditoría
            escribirAuditoria("CONSULTA_ASIGNACIONES", "ADMIN", 
                String.format("Consultadas %d mesas", mesas.size()));
            
            String resultadoStr = resultado.toString();
            logger.info("Consulta de asignaciones completada: {} mesas", mesas.size());
            
            return resultadoStr;
            
        } catch (Exception e) {
            logger.error("Error consultando asignaciones: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error en consulta: " + e.getMessage());
        } finally {
            lockAsignaciones.readLock().unlock();
        }
    }
    
    /**
     * Configura las mesas electorales del sistema.
     * 
     * @param current Contexto de Ice
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public void configurarMesas(Current current) throws ErrorPersistenciaException {
        
        logger.info("=== CONFIGURAR MESAS ===");
        
        lockMesas.writeLock().lock();
        try {
            // Mostrar configuración actual
            logger.info("Configuración actual de mesas:");
            logger.info("- Total mesas: {}", mesas.size());
            
            for (MesaInfo mesa : mesas.values()) {
                logger.info("  Mesa {}: {} - {} - Capacidad: {}", 
                    mesa.mesaId, mesa.ubicacion, mesa.estado, mesa.capacidadMaxima);
            }
            
            // Escribir auditoría
            escribirAuditoria("CONFIGURAR_MESAS", "ADMIN", 
                String.format("Acceso a configuración - %d mesas", mesas.size()));
            
            logger.info("Configuración de mesas disponible");
            
        } catch (Exception e) {
            logger.error("Error configurando mesas: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error en configuración: " + e.getMessage());
        } finally {
            lockMesas.writeLock().unlock();
        }
    }
    
    /**
     * Asigna votantes a las mesas electorales.
     * 
     * @param current Contexto de Ice
     * @throws MesaNoExisteException Si la mesa no existe
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public void asignarVotantesMesa(Current current) 
            throws MesaNoExisteException, ErrorPersistenciaException {
        
        logger.info("=== ASIGNAR VOTANTES MESA ===");
        
        lockAsignaciones.writeLock().lock();
        try {
            if (mesas.isEmpty()) {
                throw new MesaNoExisteException("NINGUNA", "No hay mesas configuradas para asignación");
            }
            
            // Procesar asignaciones automáticas de ejemplo
            procesarAsignacionesAutomaticas();
            
            // Escribir auditoría
            escribirAuditoria("ASIGNAR_VOTANTES", "ADMIN", 
                String.format("Procesadas asignaciones - Total: %d", asignaciones.size()));
            
            logger.info("Asignación de votantes completada: {} asignaciones", asignaciones.size());
            
        } catch (Exception e) {
            logger.error("Error asignando votantes: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error en asignación: " + e.getMessage());
        } finally {
            lockAsignaciones.writeLock().unlock();
        }
    }
    
    /**
     * Visualiza la distribución de votantes en las mesas.
     * 
     * @param current Contexto de Ice
     * @return String con la visualización de distribución
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public String visualizarDistribucion(Current current) throws ErrorPersistenciaException {
        
        logger.info("=== VISUALIZAR DISTRIBUCION ===");
        
        lockAsignaciones.readLock().lock();
        try {
            StringBuilder distribucion = new StringBuilder();
            distribucion.append("=== DISTRIBUCION DE VOTANTES POR MESA ===\n\n");
            
            // Estadísticas generales
            int totalAsignados = asignaciones.size();
            int capacidadTotal = mesas.values().stream()
                .mapToInt(mesa -> mesa.capacidadMaxima)
                .sum();
            
            distribucion.append(String.format("RESUMEN GENERAL:\n"));
            distribucion.append(String.format("- Total votantes asignados: %d\n", totalAsignados));
            distribucion.append(String.format("- Capacidad total sistema: %d\n", capacidadTotal));
            distribucion.append(String.format("- Ocupación general: %.1f%%\n\n", 
                (totalAsignados * 100.0) / capacidadTotal));
            
            // Distribución por mesa
            distribucion.append("DISTRIBUCION POR MESA:\n");
            
            for (MesaInfo mesa : mesas.values()) {
                long asignacionesMesa = asignaciones.values().stream()
                    .filter(asign -> asign.mesaId.equals(mesa.mesaId) && asign.confirmada)
                    .count();
                
                double porcentajeOcupacion = (asignacionesMesa * 100.0) / mesa.capacidadMaxima;
                
                distribucion.append(String.format("%s (%s):\n", mesa.mesaId, mesa.ubicacion));
                distribucion.append(String.format("  Asignados: %d/%d (%.1f%%)\n", 
                    asignacionesMesa, mesa.capacidadMaxima, porcentajeOcupacion));
                
                // Barra visual de ocupación
                int barras = (int) (porcentajeOcupacion / 5); // Cada barra = 5%
                String barraVisual = "█".repeat(Math.max(0, barras)) + 
                                   "░".repeat(Math.max(0, 20 - barras));
                distribucion.append(String.format("  [%s] %.1f%%\n", barraVisual, porcentajeOcupacion));
                
                // Estado de la mesa
                String estadoColor = getEstadoColor(mesa.estado);
                distribucion.append(String.format("  Estado: %s %s\n\n", estadoColor, mesa.estado));
            }
            
            // Escribir auditoría
            escribirAuditoria("VISUALIZAR_DISTRIBUCION", "ADMIN", 
                String.format("Generada distribución - %d mesas, %d asignados", 
                    mesas.size(), totalAsignados));
            
            String resultadoStr = distribucion.toString();
            logger.info("Distribución generada exitosamente");
            
            return resultadoStr;
            
        } catch (Exception e) {
            logger.error("Error visualizando distribución: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error en visualización: " + e.getMessage());
        } finally {
            lockAsignaciones.readLock().unlock();
        }
    }
    
    // === MÉTODOS PÚBLICOS ADICIONALES ===
    
    /**
     * Agrega una nueva mesa electoral.
     */
    public void agregarMesa(String mesaId, String ubicacion, int capacidad, String responsable, TipoMesa tipo) 
            throws ErrorPersistenciaException {
        
        lockMesas.writeLock().lock();
        try {
            if (mesas.containsKey(mesaId)) {
                throw new ErrorPersistenciaException("Mesa ya existe: " + mesaId);
            }
            
            MesaInfo nuevaMesa = new MesaInfo(mesaId, ubicacion, capacidad, responsable, EstadoMesa.CONFIGURADA, tipo);
            mesas.put(mesaId, nuevaMesa);
            
            totalMesas.incrementAndGet();
            
            // Persistir cambios
            persistirMesas();
            
            // Escribir auditoría
            escribirAuditoria("AGREGAR_MESA", mesaId, 
                String.format("Ubicación: %s, Capacidad: %d, Tipo: %s", ubicacion, capacidad, tipo));
            
            logger.info("Mesa {} agregada exitosamente", mesaId);
            
        } catch (Exception e) {
            logger.error("Error agregando mesa {}: {}", mesaId, e.getMessage());
            throw new ErrorPersistenciaException("Error agregando mesa: " + e.getMessage());
        } finally {
            lockMesas.writeLock().unlock();
        }
    }
    
    /**
     * Obtiene estadísticas del administrador de mesas.
     */
    public String obtenerEstadisticas() {
        return String.format(
            "AdministradorMesa - Mesas: %d, Asignaciones: %d, Consultas: %d",
            mesas.size(), asignaciones.size(), totalConsultas.get()
        );
    }
    
    // === MÉTODOS PRIVADOS ===
    
    /**
     * Crea mesas de ejemplo para demostración.
     */
    private void crearMesasEjemplo() {
        try {
            logger.info("Creando mesas de ejemplo...");
            
            // Mesa 1 - Centro urbano
            MesaInfo mesa1 = new MesaInfo("MESA001", "Centro Cívico Norte", 300, "Ana García", 
                EstadoMesa.CONFIGURADA, TipoMesa.ORDINARIA);
            mesas.put("MESA001", mesa1);
            
            // Mesa 2 - Colegio
            MesaInfo mesa2 = new MesaInfo("MESA002", "Colegio San José", 250, "Carlos López", 
                EstadoMesa.CONFIGURADA, TipoMesa.ORDINARIA);
            mesas.put("MESA002", mesa2);
            
            // Mesa 3 - Hospital
            MesaInfo mesa3 = new MesaInfo("MESA003", "Hospital Central", 50, "Dr. María Rodríguez", 
                EstadoMesa.CONFIGURADA, TipoMesa.HOSPITAL);
            mesas.put("MESA003", mesa3);
            
            // Mesa 4 - Especial
            MesaInfo mesa4 = new MesaInfo("MESA004", "Centro Penitenciario", 100, "Jorge Martínez", 
                EstadoMesa.CONFIGURADA, TipoMesa.CARCEL);
            mesas.put("MESA004", mesa4);
            
            totalMesas.set(mesas.size());
            
            // Persistir mesas
            persistirMesas();
            
            logger.info("Creadas {} mesas de ejemplo", mesas.size());
            
        } catch (Exception e) {
            logger.error("Error creando mesas de ejemplo: {}", e.getMessage());
        }
    }
    
    /**
     * Procesa asignaciones automáticas de ejemplo.
     */
    private void procesarAsignacionesAutomaticas() {
        try {
            logger.info("Procesando asignaciones automáticas...");
            
            // Simular asignaciones para cada mesa
            int asignacionId = 1;
            
            for (MesaInfo mesa : mesas.values()) {
                // Asignar entre 60-80% de la capacidad
                int asignacionesParaMesa = (int) (mesa.capacidadMaxima * (0.6 + Math.random() * 0.2));
                
                for (int i = 0; i < asignacionesParaMesa; i++) {
                    String cedula = String.format("%08d", 10000000 + asignacionId);
                    String asignacionIdStr = String.format("ASIGN%06d", asignacionId);
                    
                    AsignacionInfo asignacion = new AsignacionInfo(
                        cedula, mesa.mesaId, Instant.now().toString(), true
                    );
                    
                    asignaciones.put(asignacionIdStr, asignacion);
                    asignacionId++;
                }
            }
            
            totalAsignaciones.set(asignaciones.size());
            
            // Persistir asignaciones
            persistirAsignaciones();
            
            logger.info("Procesadas {} asignaciones automáticas", asignaciones.size());
            
        } catch (Exception e) {
            logger.error("Error procesando asignaciones automáticas: {}", e.getMessage());
        }
    }
    
    /**
     * Obtiene color para estado de mesa (para visualización).
     */
    private String getEstadoColor(EstadoMesa estado) {
        switch (estado) {
            case CONFIGURADA: return "🟡";
            case ACTIVA: return "🟢";
            case CERRADA: return "🔴";
            case SUSPENDIDA: return "🟠";
            default: return "⚪";
        }
    }
    
    /**
     * Inicializa archivos de persistencia.
     */
    private void inicializarArchivos() {
        try {
            Path mesasPath = Paths.get(archivoMesas);
            Path asignacionesPath = Paths.get(archivoAsignaciones);
            Path auditoriaPath = Paths.get(archivoAuditoria);
            
            // Crear directorios si no existen
            Files.createDirectories(mesasPath.getParent());
            Files.createDirectories(asignacionesPath.getParent());
            Files.createDirectories(auditoriaPath.getParent());
            
            // Crear archivos si no existen
            if (!Files.exists(mesasPath)) {
                Files.createFile(mesasPath);
                logger.info("Archivo de mesas creado: {}", archivoMesas);
            }
            
            if (!Files.exists(asignacionesPath)) {
                Files.createFile(asignacionesPath);
                logger.info("Archivo de asignaciones creado: {}", archivoAsignaciones);
            }
            
            if (!Files.exists(auditoriaPath)) {
                Files.createFile(auditoriaPath);
                logger.info("Archivo de auditoría creado: {}", archivoAuditoria);
            }
            
        } catch (Exception e) {
            logger.error("Error inicializando archivos: {}", e.getMessage());
            throw new RuntimeException("Error inicializando AdministradorMesa", e);
        }
    }
    
    /**
     * Carga datos existentes desde archivos.
     */
    private void cargarDatosExistentes() {
        cargarMesas();
        cargarAsignaciones();
    }
    
    /**
     * Carga mesas desde archivo.
     */
    private void cargarMesas() {
        try {
            Path mesasPath = Paths.get(archivoMesas);
            
            if (Files.exists(mesasPath) && Files.size(mesasPath) > 0) {
                Files.lines(mesasPath).forEach(linea -> {
                    try {
                        if (!linea.trim().isEmpty()) {
                            String[] partes = linea.split("\\|", 6);
                            if (partes.length >= 6) {
                                String mesaId = partes[0];
                                String ubicacion = partes[1];
                                int capacidad = Integer.parseInt(partes[2]);
                                String responsable = partes[3];
                                EstadoMesa estado = EstadoMesa.valueOf(partes[4]);
                                TipoMesa tipo = TipoMesa.valueOf(partes[5]);
                                
                                MesaInfo mesa = new MesaInfo(mesaId, ubicacion, capacidad, responsable, estado, tipo);
                                mesas.put(mesaId, mesa);
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Error cargando línea de mesa: {}", linea);
                    }
                });
                
                logger.info("Cargadas {} mesas desde archivo", mesas.size());
            }
            
        } catch (Exception e) {
            logger.error("Error cargando mesas: {}", e.getMessage());
        }
    }
    
    /**
     * Carga asignaciones desde archivo.
     */
    private void cargarAsignaciones() {
        try {
            Path asignacionesPath = Paths.get(archivoAsignaciones);
            
            if (Files.exists(asignacionesPath) && Files.size(asignacionesPath) > 0) {
                Files.lines(asignacionesPath).forEach(linea -> {
                    try {
                        if (!linea.trim().isEmpty()) {
                            String[] partes = linea.split("\\|", 4);
                            if (partes.length >= 4) {
                                String cedula = partes[0];
                                String mesaId = partes[1];
                                String timestamp = partes[2];
                                boolean confirmada = Boolean.parseBoolean(partes[3]);
                                
                                AsignacionInfo asignacion = new AsignacionInfo(cedula, mesaId, timestamp, confirmada);
                                asignaciones.put(cedula + "_" + mesaId, asignacion);
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Error cargando línea de asignación: {}", linea);
                    }
                });
                
                logger.info("Cargadas {} asignaciones desde archivo", asignaciones.size());
            }
            
        } catch (Exception e) {
            logger.error("Error cargando asignaciones: {}", e.getMessage());
        }
    }
    
    /**
     * Persiste mesas en archivo.
     */
    private void persistirMesas() throws IOException {
        List<String> lineas = new ArrayList<>();
        
        for (MesaInfo mesa : mesas.values()) {
            String linea = String.format("%s|%s|%d|%s|%s|%s",
                mesa.mesaId, mesa.ubicacion, mesa.capacidadMaxima,
                mesa.responsable, mesa.estado, mesa.tipo);
            lineas.add(linea);
        }
        
        Files.write(Paths.get(archivoMesas), lineas, 
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
    
    /**
     * Persiste asignaciones en archivo.
     */
    private void persistirAsignaciones() throws IOException {
        List<String> lineas = new ArrayList<>();
        
        for (AsignacionInfo asignacion : asignaciones.values()) {
            String linea = String.format("%s|%s|%s|%s",
                asignacion.cedula, asignacion.mesaId, 
                asignacion.timestamp, asignacion.confirmada);
            lineas.add(linea);
        }
        
        Files.write(Paths.get(archivoAsignaciones), lineas, 
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }
    
    /**
     * Escribe un log de auditoría.
     */
    private void escribirAuditoria(String operacion, String mesaId, String detalles) {
        lockAuditoria.writeLock().lock();
        try {
            String logEntry = String.format("AUDIT|%s|%s|%s|%s%n", 
                Instant.now().toString(), operacion, mesaId, detalles);
            
            Files.write(Paths.get(archivoAuditoria), logEntry.getBytes(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            
        } catch (Exception e) {
            logger.error("Error escribiendo auditoría: {}", e.getMessage());
        } finally {
            lockAuditoria.writeLock().unlock();
        }
    }
    
    /**
     * Finaliza el AdministradorMesa.
     */
    public void shutdown() {
        logger.info("Finalizando AdministradorMesa...");
        
        // Escribir métricas finales
        escribirAuditoria("SHUTDOWN", "SISTEMA", obtenerEstadisticas());
        
        logger.info("AdministradorMesa finalizado");
        logger.info("Estadísticas finales: {}", obtenerEstadisticas());
    }
    
    // === CLASES INTERNAS ===
    
    /**
     * Clase para representar información de una mesa electoral.
     */
    private static class MesaInfo {
        final String mesaId;
        final String ubicacion;
        final int capacidadMaxima;
        final String responsable;
        final EstadoMesa estado;
        final TipoMesa tipo;
        
        MesaInfo(String mesaId, String ubicacion, int capacidadMaxima, String responsable, 
                EstadoMesa estado, TipoMesa tipo) {
            this.mesaId = mesaId;
            this.ubicacion = ubicacion;
            this.capacidadMaxima = capacidadMaxima;
            this.responsable = responsable;
            this.estado = estado;
            this.tipo = tipo;
        }
    }
    
    /**
     * Clase para representar una asignación de votante a mesa.
     */
    private static class AsignacionInfo {
        final String cedula;
        final String mesaId;
        final String timestamp;
        final boolean confirmada;
        
        AsignacionInfo(String cedula, String mesaId, String timestamp, boolean confirmada) {
            this.cedula = cedula;
            this.mesaId = mesaId;
            this.timestamp = timestamp;
            this.confirmada = confirmada;
        }
    }
} 