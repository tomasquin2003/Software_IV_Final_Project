package com.registraduria.votacion.database.circuit;

import Votacion.CircuitBreakerService;
import Votacion.CircuitStatus;
import Votacion.DatabaseNode;
import Votacion.ErrorPersistenciaException;
import Votacion.EstadoConexion;

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
import java.util.concurrent.atomic.AtomicLong;

/**
 * CircuitBreakerService - Control de estados de nodos y políticas de reintentos.
 * 
 * Implementa el patrón Circuit Breaker para prevenir cascadas de fallos
 * y permitir recuperación automática de nodos con problemas.
 * 
 * NOTA: Implementa la interfaz CircuitBreakerService de DatabaseProxy.ice
 * Y proporciona métodos de compatibilidad para VotosBroker.ice
 */
public class CircuitBreakerServiceImpl implements CircuitBreakerService {
    private static final Logger logger = LoggerFactory.getLogger(CircuitBreakerServiceImpl.class);
    
    // Configuración de umbrales
    private static final int UMBRAL_FALLOS_CONSECUTIVOS = 5;
    private static final long TIMEOUT_MEDIO_ABIERTO = 30000; // 30 segundos
    private static final long VENTANA_EVALUACION = 60000; // 1 minuto
    
    // Estados de los circuit breakers
    private final ConcurrentHashMap<String, CircuitStatus> circuitStates;
    private final ConcurrentHashMap<String, Long> ultimosIntentos;
    private final ConcurrentHashMap<String, Integer> contadorExitos;
    
    // Archivos de configuración
    private final String archivoConfiguracion;
    private final String archivoAuditoria;
    
    // Métricas
    private volatile AtomicLong totalVerificaciones = new AtomicLong(0);
    private volatile AtomicLong circuitosAbiertos = new AtomicLong(0);
    private volatile AtomicLong circuitosCerrados = new AtomicLong(0);
    private volatile AtomicLong transicionesMedioAbierto = new AtomicLong(0);
    
    /**
     * Constructor del CircuitBreakerService.
     */
    public CircuitBreakerServiceImpl(String dataDir) {
        this.circuitStates = new ConcurrentHashMap<>();
        this.ultimosIntentos = new ConcurrentHashMap<>();
        this.contadorExitos = new ConcurrentHashMap<>();
        this.archivoConfiguracion = dataDir + "/CircuitBreakerPolicy.xml";
        this.archivoAuditoria = dataDir + "/CircuitBreakerAuditoria.log";
        
        logger.info("CircuitBreakerService inicializado");
        logger.info("Archivo configuración: {}", archivoConfiguracion);
        logger.info("Archivo auditoría: {}", archivoAuditoria);
        
        // Inicializar archivos
        inicializarArchivos();
        
        // Cargar configuración
        cargarConfiguracion();
        
        // Configurar estados iniciales
        configurarEstadosIniciales();
    }
    
    // === MÉTODOS DE LA INTERFAZ DATABASE PROXY ===
    
    /**
     * Verifica el estado del circuit breaker para un nodo (DatabaseProxy.ice).
     */
    public CircuitStatus checkCircuitStatus(DatabaseNode dbNode, Current current) 
            throws ErrorPersistenciaException {
        
        logger.debug("=== CHECK CIRCUIT STATUS ===");
        logger.debug("Nodo: {}", dbNode.nodeId);
        
        try {
            totalVerificaciones.incrementAndGet();
            
            if (dbNode == null || dbNode.nodeId == null || dbNode.nodeId.trim().isEmpty()) {
                throw new ErrorPersistenciaException("DatabaseNode o nodeId no puede ser nulo");
            }
            
            String nodeId = dbNode.nodeId;
            CircuitStatus estado = circuitStates.get(nodeId);
            
            if (estado == null) {
                // Crear estado inicial
                estado = crearEstadoInicial(nodeId);
                circuitStates.put(nodeId, estado);
            }
            
            // Evaluar y actualizar estado si es necesario
            CircuitStatus estadoActualizado = evaluarEstado(nodeId, estado);
            
            if (!estadoActualizado.abierto && estado.abierto) {
                // Transición de abierto a cerrado
                circuitosCerrados.incrementAndGet();
                escribirAuditoria("CIRCUIT_CLOSED", nodeId, 
                    String.format("Circuit cerrado - Fallos consecutivos: %d", estadoActualizado.fallosConsecutivos));
            } else if (estadoActualizado.abierto && !estado.abierto) {
                // Transición de cerrado a abierto
                circuitosAbiertos.incrementAndGet();
                escribirAuditoria("CIRCUIT_OPENED", nodeId, 
                    String.format("Circuit abierto - Fallos consecutivos: %d", estadoActualizado.fallosConsecutivos));
            }
            
            // Actualizar estado
            circuitStates.put(nodeId, estadoActualizado);
            
            logger.debug("Estado de circuit para {}: abierto={}, fallos={}", 
                nodeId, estadoActualizado.abierto, estadoActualizado.fallosConsecutivos);
            
            return estadoActualizado;
            
        } catch (Exception e) {
            logger.error("Error verificando estado de circuit para {}: {}", dbNode.nodeId, e.getMessage());
            throw new ErrorPersistenciaException("Error verificando circuit: " + e.getMessage());
        }
    }
    
    /**
     * Registra un fallo para el nodo especificado (DatabaseProxy.ice).
     */
    public void registerFailure(String target) throws ErrorPersistenciaException {
        
        logger.warn("=== REGISTER FAILURE (CIRCUIT) ===");
        logger.warn("Target: {}", target);
        
        try {
            if (target == null || target.trim().isEmpty()) {
                throw new ErrorPersistenciaException("Target no puede ser nulo o vacío");
            }
            
            CircuitStatus estado = circuitStates.get(target);
            if (estado == null) {
                estado = crearEstadoInicial(target);
            }
            
            // Incrementar contador de fallos
            CircuitStatus nuevoEstado = new CircuitStatus();
            nuevoEstado.nodo = target;
            nuevoEstado.fallosConsecutivos = estado.fallosConsecutivos + 1;
            nuevoEstado.ultimoFallo = Instant.now().toString();
            
            // Determinar si debe abrir el circuit
            if (nuevoEstado.fallosConsecutivos >= UMBRAL_FALLOS_CONSECUTIVOS) {
                nuevoEstado.abierto = true;
                logger.warn("Circuit ABIERTO para {} - {} fallos consecutivos", target, nuevoEstado.fallosConsecutivos);
            } else {
                nuevoEstado.abierto = estado.abierto;
            }
            
            circuitStates.put(target, nuevoEstado);
            
            // Limpiar contador de éxitos
            contadorExitos.put(target, 0);
            
            // Escribir auditoría
            escribirAuditoria("REGISTER_FAILURE", target, 
                String.format("Fallos consecutivos: %d, Circuit abierto: %s", 
                    nuevoEstado.fallosConsecutivos, nuevoEstado.abierto));
            
            logger.warn("Fallo registrado para {} - Total fallos consecutivos: {}", 
                target, nuevoEstado.fallosConsecutivos);
            
        } catch (Exception e) {
            logger.error("Error registrando fallo en circuit para {}: {}", target, e.getMessage());
            throw new ErrorPersistenciaException("Error registrando fallo en circuit: " + e.getMessage());
        }
    }
    
    /**
     * Registra un éxito para el nodo especificado (DatabaseProxy.ice).
     */
    public void registerSuccess(String target) throws ErrorPersistenciaException {
        
        logger.debug("=== REGISTER SUCCESS (CIRCUIT) ===");
        logger.debug("Target: {}", target);
        
        try {
            if (target == null || target.trim().isEmpty()) {
                throw new ErrorPersistenciaException("Target no puede ser nulo o vacío");
            }
            
            CircuitStatus estado = circuitStates.get(target);
            if (estado == null) {
                estado = crearEstadoInicial(target);
            }
            
            // Incrementar contador de éxitos
            int exitosActuales = contadorExitos.getOrDefault(target, 0) + 1;
            contadorExitos.put(target, exitosActuales);
            
            // Si el circuit estaba abierto y tenemos suficientes éxitos, cerrarlo
            if (estado.abierto && exitosActuales >= 3) { // Requerir 3 éxitos para cerrar
                CircuitStatus nuevoEstado = new CircuitStatus();
                nuevoEstado.nodo = target;
                nuevoEstado.abierto = false;
                nuevoEstado.fallosConsecutivos = 0;
                nuevoEstado.ultimoFallo = "";
                
                circuitStates.put(target, nuevoEstado);
                contadorExitos.put(target, 0);
                
                logger.info("Circuit CERRADO para {} después de {} éxitos", target, exitosActuales);
                
                escribirAuditoria("CIRCUIT_RECOVERED", target, 
                    String.format("Circuit recuperado después de %d éxitos", exitosActuales));
            } else if (!estado.abierto) {
                // Si ya estaba cerrado, resetear contador de fallos
                CircuitStatus nuevoEstado = new CircuitStatus();
                nuevoEstado.nodo = target;
                nuevoEstado.abierto = false;
                nuevoEstado.fallosConsecutivos = 0;
                nuevoEstado.ultimoFallo = estado.ultimoFallo;
                
                circuitStates.put(target, nuevoEstado);
            }
            
            logger.debug("Éxito registrado para {} - Éxitos consecutivos: {}", target, exitosActuales);
            
        } catch (Exception e) {
            logger.error("Error registrando éxito en circuit para {}: {}", target, e.getMessage());
            throw new ErrorPersistenciaException("Error registrando éxito en circuit: " + e.getMessage());
        }
    }
    
    // === MÉTODOS DE COMPATIBILIDAD PARA VOTOSBROKER ===
    
    /**
     * Verifica si el circuit breaker está abierto para un destino (VotosBroker.ice).
     * Método de compatibilidad - NO es @Override porque no implementamos esa interfaz.
     */
    public boolean verificarCircuitoAbierto(String destino, Current current) {
        try {
            CircuitStatus estado = circuitStates.get(destino);
            if (estado == null) {
                return false; // Si no existe, está cerrado
            }
            return estado.abierto;
        } catch (Exception e) {
            logger.error("Error verificando circuito para {}: {}", destino, e.getMessage());
            return true; // En caso de error, asumir abierto por seguridad
        }
    }
    
    /**
     * Método de compatibilidad para la interfaz VotosBroker - registrar fallo.
     * Método de compatibilidad - NO es @Override porque no implementamos esa interfaz.
     */
    public void registrarFallo(String destino, Current current) {
        try {
            registerFailure(destino);
        } catch (ErrorPersistenciaException e) {
            logger.error("Error registrando fallo para {}: {}", destino, e.getMessage());
        }
    }
    
    /**
     * Método de compatibilidad para la interfaz VotosBroker - registrar éxito.
     * Método de compatibilidad - NO es @Override porque no implementamos esa interfaz.
     */
    public void registrarExito(String destino, Current current) {
        try {
            registerSuccess(destino);
        } catch (ErrorPersistenciaException e) {
            logger.error("Error registrando éxito para {}: {}", destino, e.getMessage());
        }
    }
    
    // === MÉTODOS PRIVADOS ===
    
    /**
     * Inicializa archivos necesarios.
     */
    private void inicializarArchivos() {
        try {
            Path configPath = Paths.get(archivoConfiguracion);
            Path auditoriaPath = Paths.get(archivoAuditoria);
            
            // Crear directorios si no existen
            Files.createDirectories(configPath.getParent());
            Files.createDirectories(auditoriaPath.getParent());
            
            // Crear archivo de configuración si no existe
            if (!Files.exists(configPath)) {
                crearArchivoConfiguracion();
                logger.info("Archivo de configuración creado: {}", archivoConfiguracion);
            }
            
            // Crear archivo de auditoría si no existe
            if (!Files.exists(auditoriaPath)) {
                Files.createFile(auditoriaPath);
                logger.info("Archivo de auditoría creado: {}", archivoAuditoria);
            }
            
            // Escribir inicio de sesión
            escribirAuditoria("INIT", "SISTEMA", "CircuitBreakerService inicializado");
            
        } catch (Exception e) {
            logger.error("Error inicializando archivos: {}", e.getMessage());
        }
    }
    
    /**
     * Crea el archivo de configuración XML.
     */
    private void crearArchivoConfiguracion() throws IOException {
        String configXml = 
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<CircuitBreakerPolicy>\n" +
            "    <GlobalSettings>\n" +
            "        <FailureThreshold>5</FailureThreshold>\n" +
            "        <HalfOpenTimeout>30000</HalfOpenTimeout>\n" +
            "        <EvaluationWindow>60000</EvaluationWindow>\n" +
            "        <SuccessThreshold>3</SuccessThreshold>\n" +
            "    </GlobalSettings>\n" +
            "    \n" +
            "    <NodePolicies>\n" +
            "        <Node id=\"RDBMS_PRIMARY\">\n" +
            "            <FailureThreshold>3</FailureThreshold>\n" +
            "            <Priority>HIGH</Priority>\n" +
            "        </Node>\n" +
            "        <Node id=\"RDBMS_REPLICA\">\n" +
            "            <FailureThreshold>5</FailureThreshold>\n" +
            "            <Priority>MEDIUM</Priority>\n" +
            "        </Node>\n" +
            "    </NodePolicies>\n" +
            "</CircuitBreakerPolicy>\n";
        
        Files.write(Paths.get(archivoConfiguracion), configXml.getBytes());
    }
    
    /**
     * Carga configuración desde archivo XML.
     */
    private void cargarConfiguracion() {
        try {
            // En una implementación real parsearia el XML
            // Por ahora usar configuración por defecto
            logger.info("Configuración de circuit breaker cargada");
            logger.info("- Umbral fallos: {}", UMBRAL_FALLOS_CONSECUTIVOS);
            logger.info("- Timeout medio abierto: {}ms", TIMEOUT_MEDIO_ABIERTO);
            logger.info("- Ventana evaluación: {}ms", VENTANA_EVALUACION);
            
        } catch (Exception e) {
            logger.error("Error cargando configuración: {}", e.getMessage());
        }
    }
    
    /**
     * Configura estados iniciales para nodos conocidos.
     */
    private void configurarEstadosIniciales() {
        try {
            // Configurar Primary
            CircuitStatus primaryStatus = crearEstadoInicial("RDBMS_PRIMARY");
            circuitStates.put("RDBMS_PRIMARY", primaryStatus);
            
            // Configurar Replica
            CircuitStatus replicaStatus = crearEstadoInicial("RDBMS_REPLICA");
            circuitStates.put("RDBMS_REPLICA", replicaStatus);
            
            logger.info("Estados iniciales configurados para {} nodos", circuitStates.size());
            
        } catch (Exception e) {
            logger.error("Error configurando estados iniciales: {}", e.getMessage());
        }
    }
    
    /**
     * Crea un estado inicial para un nodo.
     */
    private CircuitStatus crearEstadoInicial(String nodeId) {
        CircuitStatus estado = new CircuitStatus();
        estado.nodo = nodeId;
        estado.abierto = false;
        estado.fallosConsecutivos = 0;
        estado.ultimoFallo = "";
        return estado;
    }
    
    /**
     * Evalúa y actualiza el estado de un circuit breaker.
     */
    private CircuitStatus evaluarEstado(String nodeId, CircuitStatus estadoActual) {
        try {
            // Si está cerrado, no hay nada que evaluar
            if (!estadoActual.abierto) {
                return estadoActual;
            }
            
            // Si está abierto, verificar si puede pasar a medio abierto
            Long ultimoIntento = ultimosIntentos.get(nodeId);
            long tiempoActual = System.currentTimeMillis();
            
            if (ultimoIntento == null || (tiempoActual - ultimoIntento) > TIMEOUT_MEDIO_ABIERTO) {
                // Transición a medio abierto
                transicionesMedioAbierto.incrementAndGet();
                ultimosIntentos.put(nodeId, tiempoActual);
                
                logger.info("Circuit para {} pasando a estado MEDIO ABIERTO", nodeId);
                escribirAuditoria("HALF_OPEN_TRANSITION", nodeId, "Transición a medio abierto");
                
                // En estado medio abierto, permitir algunas operaciones de prueba
                // El estado sigue siendo "abierto" pero permitimos pruebas
            }
            
            return estadoActual;
            
        } catch (Exception e) {
            logger.error("Error evaluando estado de circuit para {}: {}", nodeId, e.getMessage());
            return estadoActual;
        }
    }
    
    /**
     * Escribe un log de auditoría.
     */
    private void escribirAuditoria(String operacion, String nodo, String detalles) {
        try {
            String logEntry = String.format("CIRCUIT|%s|%s|%s|%s%n", 
                Instant.now().toString(), operacion, nodo, detalles);
            
            Files.write(Paths.get(archivoAuditoria), logEntry.getBytes(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            
        } catch (Exception e) {
            logger.error("Error escribiendo auditoría de circuit breaker: {}", e.getMessage());
        }
    }
    
    /**
     * Obtiene estadísticas del CircuitBreakerService.
     */
    public String obtenerEstadisticas() {
        int circuitsActivos = circuitStates.size();
        long circuitsAbiertosActuales = circuitStates.values().stream()
            .filter(status -> status.abierto)
            .count();
        
        return String.format(
            "CircuitBreaker - Verificaciones: %d, Circuits: %d, Abiertos: %d, Transiciones: %d",
            totalVerificaciones.get(), circuitsActivos, 
            circuitsAbiertosActuales, transicionesMedioAbierto.get()
        );
    }
    
    /**
     * Finaliza el CircuitBreakerService.
     */
    public void shutdown() {
        logger.info("Finalizando CircuitBreakerService...");
        
        // Escribir estadísticas finales
        escribirAuditoria("SHUTDOWN", "SISTEMA", obtenerEstadisticas());
        
        logger.info("CircuitBreakerService finalizado");
        logger.info("Estadísticas finales: {}", obtenerEstadisticas());
    }
} 