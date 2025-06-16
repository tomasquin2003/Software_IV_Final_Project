package com.registraduria.votacion.database.failover;

import Votacion.ConnectionInfo;
import Votacion.DatabaseConnectionException;
import Votacion.ErrorPersistenciaException;
import Votacion.EstadoConexion;
import Votacion.FailoverHandler;

import com.registraduria.votacion.database.circuit.CircuitBreakerServiceImpl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.zeroc.Ice.Current;

/**
 * FailoverHandler - Gestiona detección y conmutación automática ante fallos.
 * 
 * Mantiene el estado de las conexiones de base de datos y maneja la
 * conmutación automática cuando se detectan fallos en los nodos.
 */
public class FailoverHandlerImpl implements FailoverHandler {
    private static final Logger logger = LoggerFactory.getLogger(FailoverHandlerImpl.class);
    
    // Configuración de nodos
    private static final String PRIMARY_NODE = "RDBMS_PRIMARY";
    private static final String REPLICA_NODE = "RDBMS_REPLICA";
    
    // Umbrales de configuración
    private static final int MAX_REINTENTOS = 3;
    private static final long TIMEOUT_RECUPERACION = 30000; // 30 segundos
    
    // Dependencias
    private final CircuitBreakerServiceImpl circuitBreakerService;
    
    // Estado de conexiones
    private final ConcurrentHashMap<String, ConnectionInfo> conexionesActivas;
    private final ConcurrentHashMap<String, Long> ultimosIntentos;
    
    // Archivo de auditoría
    private final String archivoAuditoria;
    
    // Métricas
    private volatile AtomicLong totalConexiones = new AtomicLong(0);
    private volatile AtomicLong fallosDetectados = new AtomicLong(0);
    private volatile AtomicLong conmutacionesExitosas = new AtomicLong(0);
    private volatile AtomicLong recuperacionesExitosas = new AtomicLong(0);
    
    /**
     * Constructor del FailoverHandler.
     */
    public FailoverHandlerImpl(CircuitBreakerServiceImpl circuitBreakerService, String dataDir) {
        this.circuitBreakerService = circuitBreakerService;
        this.conexionesActivas = new ConcurrentHashMap<>();
        this.ultimosIntentos = new ConcurrentHashMap<>();
        this.archivoAuditoria = dataDir + "/FailoverAuditoria.log";
        
        logger.info("FailoverHandler inicializado");
        logger.info("Archivo auditoría: {}", archivoAuditoria);
        
        // Inicializar archivos
        inicializarArchivos();
        
        // Configurar conexiones por defecto
        configurarConexionesDefecto();
    }
    
    /**
     * Obtiene una conexión proxy al target especificado.
     */
    public ConnectionInfo getProxiedConnection(String target) 
            throws ErrorPersistenciaException, DatabaseConnectionException {
        
        logger.debug("=== GET PROXIED CONNECTION ===");
        logger.debug("Target: {}", target);
        
        try {
            totalConexiones.incrementAndGet();
            
            if (target == null || target.trim().isEmpty()) {
                throw new ErrorPersistenciaException("Target no puede ser nulo o vacío");
            }
            
            // Verificar si la conexión está disponible
            ConnectionInfo conexion = conexionesActivas.get(target);
            
            if (conexion == null) {
                // Crear nueva conexión
                conexion = crearNuevaConexion(target);
                conexionesActivas.put(target, conexion);
            }
            
            // Verificar estado de la conexión
            if (conexion.estado == EstadoConexion.FALLIDA) {
                // Intentar recuperar conexión si ha pasado suficiente tiempo
                if (puedeIntentarRecuperacion(target)) {
                    conexion = intentarRecuperarConexion(target);
                } else {
                    // Buscar conexión alternativa
                    conexion = buscarConexionAlternativa(target);
                }
            }
            
            // Actualizar última actividad
            conexion = actualizarUltimaActividad(conexion);
            conexionesActivas.put(target, conexion);
            
            // Escribir auditoría
            escribirAuditoria("GET_CONNECTION", target, 
                String.format("Estado: %s, Host: %s", conexion.estado, conexion.host));
            
            logger.debug("Conexión obtenida para {}: {} ({})", target, conexion.host, conexion.estado);
            return conexion;
            
        } catch (DatabaseConnectionException e) {
            escribirAuditoria("CONNECTION_ERROR", target, "Error: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("Error obteniendo conexión para {}: {}", target, e.getMessage());
            escribirAuditoria("CONNECTION_UNEXPECTED_ERROR", target, "Error: " + e.getMessage());
            throw new ErrorPersistenciaException("Error inesperado obteniendo conexión: " + e.getMessage());
        }
    }
    
    /**
     * Obtiene una conexión proxy al target especificado (Versión con Current).
     */
    public ConnectionInfo getProxiedConnection(String target, Current current) 
            throws ErrorPersistenciaException, DatabaseConnectionException {
        // Delegar a la versión sin Current
        return getProxiedConnection(target);
    }
    
    /**
     * Registra un fallo en el target especificado.
     */
    public void registerFailure(String target) throws ErrorPersistenciaException {
        
        logger.warn("=== REGISTER FAILURE ===");
        logger.warn("Target: {}", target);
        
        try {
            fallosDetectados.incrementAndGet();
            
            if (target == null || target.trim().isEmpty()) {
                throw new ErrorPersistenciaException("Target no puede ser nulo o vacío");
            }
            
            // Actualizar estado de conexión
            ConnectionInfo conexion = conexionesActivas.get(target);
            if (conexion != null) {
                ConnectionInfo conexionFallida = new ConnectionInfo();
                conexionFallida.nodoId = conexion.nodoId;
                conexionFallida.host = conexion.host;
                conexionFallida.puerto = conexion.puerto;
                conexionFallida.estado = EstadoConexion.FALLIDA;
                conexionFallida.ultimaActividad = Instant.now().toString();
                
                conexionesActivas.put(target, conexionFallida);
            }
            
            // Registrar fallo en circuit breaker (sin Current)
            try {
                circuitBreakerService.registerFailure(target);
            } catch (Exception cbException) {
                logger.error("Error registrando fallo en circuit breaker para {}: {}", target, cbException.getMessage());
            }
            
            // Escribir auditoría
            escribirAuditoria("REGISTER_FAILURE", target, 
                String.format("Fallo registrado - Total fallos: %d", fallosDetectados.get()));
            
            logger.warn("Fallo registrado para {}", target);
            
        } catch (Exception e) {
            logger.error("Error registrando fallo para {}: {}", target, e.getMessage());
            throw new ErrorPersistenciaException("Error registrando fallo: " + e.getMessage());
        }
    }
    
    /**
     * Registra un fallo en el target especificado (Versión con Current).
     */
    public void registerFailure(String target, Current current) throws ErrorPersistenciaException {
        // Delegar a la versión sin Current
        registerFailure(target);
    }
    
    /**
     * Registra un éxito en el target especificado.
     */
    public void registerSuccess(String target) throws ErrorPersistenciaException {
        
        logger.debug("=== REGISTER SUCCESS ===");
        logger.debug("Target: {}", target);
        
        try {
            if (target == null || target.trim().isEmpty()) {
                throw new ErrorPersistenciaException("Target no puede ser nulo o vacío");
            }
            
            // Actualizar estado de conexión
            ConnectionInfo conexion = conexionesActivas.get(target);
            if (conexion != null) {
                ConnectionInfo conexionActiva = new ConnectionInfo();
                conexionActiva.nodoId = conexion.nodoId;
                conexionActiva.host = conexion.host;
                conexionActiva.puerto = conexion.puerto;
                conexionActiva.estado = EstadoConexion.ACTIVA;
                conexionActiva.ultimaActividad = Instant.now().toString();
                
                conexionesActivas.put(target, conexionActiva);
            }
            
            // Registrar éxito en circuit breaker (sin Current)
            try {
                circuitBreakerService.registerSuccess(target);
            } catch (Exception cbException) {
                logger.error("Error registrando éxito en circuit breaker para {}: {}", target, cbException.getMessage());
            }
            
            // Registrar recuperación si es apropiado
            if (conexion != null && conexion.estado == EstadoConexion.FALLIDA) {
                recuperacionesExitosas.incrementAndGet();
                escribirAuditoria("CONNECTION_RECOVERED", target, 
                    String.format("Conexión recuperada - Total recuperaciones: %d", recuperacionesExitosas.get()));
                logger.info("Conexión recuperada para {}", target);
            }
            
            // Escribir auditoría
            escribirAuditoria("REGISTER_SUCCESS", target, "Éxito registrado");
            
            logger.debug("Éxito registrado para {}", target);
            
        } catch (Exception e) {
            logger.error("Error registrando éxito para {}: {}", target, e.getMessage());
            throw new ErrorPersistenciaException("Error registrando éxito: " + e.getMessage());
        }
    }
    
    /**
     * Registra un éxito en el target especificado (Versión con Current).
     */
    public void registerSuccess(String target, Current current) throws ErrorPersistenciaException {
        // Delegar a la versión sin Current
        registerSuccess(target);
    }
    
    // === MÉTODOS PRIVADOS ===
    
    /**
     * Inicializa archivos necesarios.
     */
    private void inicializarArchivos() {
        try {
            Path auditoriaPath = Paths.get(archivoAuditoria);
            
            // Crear directorio si no existe
            Files.createDirectories(auditoriaPath.getParent());
            
            // Crear archivo si no existe
            if (!Files.exists(auditoriaPath)) {
                Files.createFile(auditoriaPath);
                logger.info("Archivo de auditoría creado: {}", archivoAuditoria);
            }
            
            // Escribir inicio de sesión
            escribirAuditoria("INIT", "SISTEMA", "FailoverHandler inicializado");
            
        } catch (Exception e) {
            logger.error("Error inicializando archivos: {}", e.getMessage());
        }
    }
    
    /**
     * Configura conexiones por defecto.
     */
    private void configurarConexionesDefecto() {
        try {
            // Configurar Primary
            ConnectionInfo primary = new ConnectionInfo();
            primary.nodoId = PRIMARY_NODE;
            primary.host = "localhost";
            primary.puerto = 5432;
            primary.estado = EstadoConexion.ACTIVA;
            primary.ultimaActividad = Instant.now().toString();
            conexionesActivas.put(PRIMARY_NODE, primary);
            
            // Configurar Replica
            ConnectionInfo replica = new ConnectionInfo();
            replica.nodoId = REPLICA_NODE;
            replica.host = "localhost";
            replica.puerto = 5433;
            replica.estado = EstadoConexion.ACTIVA;
            replica.ultimaActividad = Instant.now().toString();
            conexionesActivas.put(REPLICA_NODE, replica);
            
            logger.info("Conexiones por defecto configuradas: Primary({}), Replica({})", 
                primary.puerto, replica.puerto);
            
        } catch (Exception e) {
            logger.error("Error configurando conexiones por defecto: {}", e.getMessage());
        }
    }
    
    /**
     * Crea una nueva conexión para el target.
     */
    private ConnectionInfo crearNuevaConexion(String target) {
        ConnectionInfo conexion = new ConnectionInfo();
        conexion.nodoId = target;
        conexion.estado = EstadoConexion.ACTIVA;
        conexion.ultimaActividad = Instant.now().toString();
        
        // Configurar según el tipo de target
        if (PRIMARY_NODE.equals(target)) {
            conexion.host = "localhost";
            conexion.puerto = 5432;
        } else if (REPLICA_NODE.equals(target)) {
            conexion.host = "localhost";
            conexion.puerto = 5433;
        } else {
            conexion.host = "localhost";
            conexion.puerto = 5434;
        }
        
        return conexion;
    }
    
    /**
     * Verifica si puede intentar recuperar la conexión.
     */
    private boolean puedeIntentarRecuperacion(String target) {
        Long ultimoIntento = ultimosIntentos.get(target);
        if (ultimoIntento == null) {
            return true;
        }
        
        return (System.currentTimeMillis() - ultimoIntento) > TIMEOUT_RECUPERACION;
    }
    
    /**
     * Intenta recuperar una conexión fallida.
     */
    private ConnectionInfo intentarRecuperarConexion(String target) throws DatabaseConnectionException {
        logger.info("Intentando recuperar conexión para {}", target);
        
        ultimosIntentos.put(target, System.currentTimeMillis());
        
        // Simular intento de recuperación
        // En implementación real haría ping a la base de datos
        boolean recuperacionExitosa = Math.random() > 0.3; // 70% de éxito
        
        ConnectionInfo conexion = conexionesActivas.get(target);
        if (conexion == null) {
            conexion = crearNuevaConexion(target);
        }
        
        if (recuperacionExitosa) {
            conexion.estado = EstadoConexion.RECUPERANDO;
            escribirAuditoria("RECOVERY_SUCCESS", target, "Recuperación exitosa");
            logger.info("Recuperación exitosa para {}", target);
        } else {
            escribirAuditoria("RECOVERY_FAILED", target, "Recuperación fallida");
            throw new DatabaseConnectionException(target, "No se pudo recuperar la conexión");
        }
        
        return conexion;
    }
    
    /**
     * Busca una conexión alternativa.
     */
    private ConnectionInfo buscarConexionAlternativa(String target) throws DatabaseConnectionException {
        logger.info("Buscando conexión alternativa para {}", target);
        
        String alternativo;
        if (PRIMARY_NODE.equals(target)) {
            alternativo = REPLICA_NODE;
        } else {
            alternativo = PRIMARY_NODE;
        }
        
        ConnectionInfo conexionAlternativa = conexionesActivas.get(alternativo);
        if (conexionAlternativa != null && conexionAlternativa.estado == EstadoConexion.ACTIVA) {
            conmutacionesExitosas.incrementAndGet();
            escribirAuditoria("FAILOVER_SUCCESS", target, 
                String.format("Conmutado a %s", alternativo));
            logger.info("Conmutación exitosa de {} a {}", target, alternativo);
            return conexionAlternativa;
        }
        
        escribirAuditoria("FAILOVER_FAILED", target, "No hay alternativas disponibles");
        throw new DatabaseConnectionException(target, "No hay conexiones alternativas disponibles");
    }
    
    /**
     * Actualiza la última actividad de una conexión.
     */
    private ConnectionInfo actualizarUltimaActividad(ConnectionInfo conexion) {
        ConnectionInfo actualizada = new ConnectionInfo();
        actualizada.nodoId = conexion.nodoId;
        actualizada.host = conexion.host;
        actualizada.puerto = conexion.puerto;
        actualizada.estado = conexion.estado;
        actualizada.ultimaActividad = Instant.now().toString();
        return actualizada;
    }
    
    /**
     * Escribe un log de auditoría.
     */
    private void escribirAuditoria(String operacion, String target, String detalles) {
        try {
            String logEntry = String.format("FAILOVER|%s|%s|%s|%s%n", 
                Instant.now().toString(), operacion, target, detalles);
            
            Files.write(Paths.get(archivoAuditoria), logEntry.getBytes(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            
        } catch (Exception e) {
            logger.error("Error escribiendo auditoría de failover: {}", e.getMessage());
        }
    }
    
    /**
     * Obtiene estadísticas del FailoverHandler.
     */
    public String obtenerEstadisticas() {
        return String.format(
            "FailoverHandler - Conexiones: %d, Fallos: %d, Conmutaciones: %d, Recuperaciones: %d",
            totalConexiones.get(), fallosDetectados.get(), 
            conmutacionesExitosas.get(), recuperacionesExitosas.get()
        );
    }
    
    /**
     * Finaliza el FailoverHandler.
     */
    public void shutdown() {
        logger.info("Finalizando FailoverHandler...");
        
        // Escribir estadísticas finales
        escribirAuditoria("SHUTDOWN", "SISTEMA", obtenerEstadisticas());
        
        logger.info("FailoverHandler finalizado");
        logger.info("Estadísticas finales: {}", obtenerEstadisticas());
    }
} 