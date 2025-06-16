package com.registraduria.votacion.broker.manager;

import Votacion.BrokerManager;
import Votacion.CircuitBreakerException;
import Votacion.ControllerCentralPrx;
import Votacion.ErrorPersistenciaException;
import Votacion.EstadoVoto;
import Votacion.PrioridadVoto;
import Votacion.QueueFullException;
import Votacion.VotoDuplicadoException;

import com.registraduria.votacion.broker.circuit.CircuitBreakerServiceImpl;
import com.registraduria.votacion.broker.persistence.PersistenceManagerImpl;
import com.registraduria.votacion.broker.queue.QueueServiceImpl;
import com.registraduria.votacion.broker.retry.RetryHandlerImpl;

import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.time.Instant;

/**
 * BrokerManager - Interfaz principal expuesta al exterior para recibir 
 * peticiones de transmisión y confirmación de votos.
 * 
 * Provee servicios a MotorEmisionVotos (remoto) y coordina con todos los 
 * componentes internos del VotosBroker.
 */
public class BrokerManagerImpl implements BrokerManager {
    private static final Logger logger = LoggerFactory.getLogger(BrokerManagerImpl.class);
    
    // Componentes internos
    private final QueueServiceImpl queueService;
    private final PersistenceManagerImpl persistenceManager;
    private final CircuitBreakerServiceImpl circuitBreakerService;
    private final ControllerCentralPrx controllerCentralPrx;
    private final RetryHandlerImpl retryHandler;
    
    // Executor para operaciones asíncronas
    private final ExecutorService asyncExecutor;
    
    // Métricas del broker
    private volatile long totalVotosRecibidos = 0;
    private volatile long totalVotosTransmitidos = 0;
    private volatile long totalVotosConfirmados = 0;
    private volatile long totalErrores = 0;
    
    /**
     * Constructor del BrokerManager con todas las dependencias.
     */
    public BrokerManagerImpl(QueueServiceImpl queueService,
                           PersistenceManagerImpl persistenceManager,
                           CircuitBreakerServiceImpl circuitBreakerService,
                           ControllerCentralPrx controllerCentralPrx,
                           RetryHandlerImpl retryHandler) {
        this.queueService = queueService;
        this.persistenceManager = persistenceManager;
        this.circuitBreakerService = circuitBreakerService;
        this.controllerCentralPrx = controllerCentralPrx;
        this.retryHandler = retryHandler;
        this.asyncExecutor = Executors.newFixedThreadPool(5);
        
        logger.info("BrokerManager inicializado con todos los componentes");
    }
    
    /**
     * Transmite un voto al ServidorCentral de forma asíncrona y confiable.
     * 
     * @param votoId ID único del voto
     * @param candidatoId ID del candidato
     * @param hash Hash de verificación del voto
     * @param current Contexto de Ice
     * @throws ErrorPersistenciaException Si hay error de persistencia
     * @throws CircuitBreakerException Si el circuit breaker está abierto
     */
    @Override
    public void transmitirVotoServidor(String votoId, String candidatoId, String hash, Current current) 
            throws ErrorPersistenciaException, CircuitBreakerException {
        
        logger.info("=== TRANSMITIR VOTO SERVIDOR ===");
        logger.info("VotoId: {}, CandidatoId: {}, Hash: {}", votoId, candidatoId, hash);
        
        try {
            totalVotosRecibidos++;
            
            // Validar parámetros
            if (votoId == null || votoId.trim().isEmpty()) {
                throw new ErrorPersistenciaException("VotoId no puede ser nulo o vacío");
            }
            if (candidatoId == null || candidatoId.trim().isEmpty()) {
                throw new ErrorPersistenciaException("CandidatoId no puede ser nulo o vacío");
            }
            if (hash == null || hash.trim().isEmpty()) {
                throw new ErrorPersistenciaException("Hash no puede ser nulo o vacío");
            }
            
            // Obtener estacionId del contexto Ice
            String estacionId = obtenerEstacionIdDelContexto(current);
            
            // Verificar estado del Circuit Breaker
            if (circuitBreakerService.verificarCircuitoAbierto("ServidorCentral", null)) {
                logger.warn("Circuit Breaker ABIERTO para ServidorCentral. Encolando voto: {}", votoId);
                encolarVotoParaReintento(votoId, candidatoId, estacionId, hash, PrioridadVoto.ALTA);
                throw new CircuitBreakerException("ServidorCentral", 
                    "Circuit Breaker abierto. Voto encolado para reintento.");
            }
            
            // Crear datos del voto para persistencia
            String datosVoto = crearDatosVoto(votoId, candidatoId, estacionId, hash);
            
            // Almacenar voto como pendiente
            persistenceManager.almacenarVotoPendienteInterno(votoId, 
                new com.registraduria.votacion.broker.model.DatosBroker(
                    votoId, datosVoto, java.time.Instant.now().toString(), EstadoVoto.PENDIENTE));
            
            // Encolar voto con prioridad normal
            queueService.encolarVoto(votoId, candidatoId, PrioridadVoto.NORMAL, null);
            
            // Intentar transmisión inmediata asíncrona
            CompletableFuture.runAsync(() -> {
                try {
                    transmitirVotoInmediato(votoId, candidatoId, estacionId, hash);
                } catch (Exception e) {
                    logger.error("Error en transmisión inmediata de voto {}: {}", votoId, e.getMessage());
                    // El voto queda encolado para reintento posterior
                }
            }, asyncExecutor);
            
            logger.info("Voto {} encolado exitosamente para transmisión", votoId);
            
        } catch (QueueFullException e) {
            totalErrores++;
            logger.error("Cola llena para voto {}: {}", votoId, e.getMessage());
            throw new ErrorPersistenciaException("Cola de transmisión llena: " + e.getMessage());
        } catch (Exception e) {
            totalErrores++;
            logger.error("Error transmitiendo voto {}: {}", votoId, e.getMessage());
            throw new ErrorPersistenciaException("Error en transmisión: " + e.getMessage());
        }
    }
    
    /**
     * Confirma que un voto fue enviado exitosamente al ServidorCentral.
     * 
     * @param votoId ID del voto confirmado
     * @param estado Estado de confirmación
     * @param current Contexto de Ice
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public void confirmarEnvioServidor(String votoId, EstadoVoto estado, Current current) 
            throws ErrorPersistenciaException {
        
        logger.info("=== CONFIRMAR ENVIO SERVIDOR ===");
        logger.info("VotoId: {}, Estado: {}", votoId, estado);
        
        try {
            // Validar parámetros
            if (votoId == null || votoId.trim().isEmpty()) {
                throw new ErrorPersistenciaException("VotoId no puede ser nulo o vacío");
            }
            
            // Marcar voto como enviado en persistencia
            persistenceManager.marcarVotoEnviado(votoId, null);
            
            // Registrar éxito en Circuit Breaker
            circuitBreakerService.registrarExito("ServidorCentral", null);
            
            // Actualizar métricas
            if (estado == EstadoVoto.PROCESADO || estado == EstadoVoto.RECIBIDO) {
                totalVotosConfirmados++;
                logger.info("Voto {} confirmado exitosamente con estado: {}", votoId, estado);
            } else {
                logger.warn("Voto {} confirmado con estado inusual: {}", votoId, estado);
            }
            
        } catch (Exception e) {
            totalErrores++;
            logger.error("Error confirmando envío de voto {}: {}", votoId, e.getMessage());
            throw new ErrorPersistenciaException("Error en confirmación: " + e.getMessage());
        }
    }
    
    /**
     * Transmite un voto inmediatamente al ServidorCentral.
     */
    private void transmitirVotoInmediato(String votoId, String candidatoId, String estacionId, String hash) {
        try {
            logger.debug("Transmitiendo voto inmediato: {} -> ServidorCentral", votoId);
            
            // Llamar al ServidorCentral
            controllerCentralPrx.recibirVotoDesdeEstacion(votoId, candidatoId, estacionId, hash);
            
            // Confirmar recepción
            controllerCentralPrx.confirmarRecepcionCentral(votoId, EstadoVoto.RECIBIDO);
            
            // Marcar como transmitido exitosamente
            persistenceManager.marcarVotoEnviado(votoId, null);
            circuitBreakerService.registrarExito("ServidorCentral", null);
            
            totalVotosTransmitidos++;
            logger.info("Voto {} transmitido exitosamente a ServidorCentral", votoId);
            
        } catch (Exception e) {
            // Registrar fallo en Circuit Breaker
            circuitBreakerService.registrarFallo("ServidorCentral", null);
            logger.error("Error transmitiendo voto inmediato {}: {}", votoId, e.getMessage());
            throw new RuntimeException("Error en transmisión inmediata", e);
        }
    }
    
    /**
     * Encola un voto para reintento posterior.
     */
    private void encolarVotoParaReintento(String votoId, String candidatoId, String estacionId, 
                                        String hash, PrioridadVoto prioridad) {
        try {
            // Crear datos del voto
            String datosVoto = crearDatosVoto(votoId, candidatoId, estacionId, hash);
            
            // Almacenar en persistencia
            persistenceManager.almacenarVotoPendienteInterno(votoId, 
                new com.registraduria.votacion.broker.model.DatosBroker(
                    votoId, datosVoto, java.time.Instant.now().toString(), EstadoVoto.PENDIENTE));
            
            // Encolar para reintento
            queueService.encolarVoto(votoId, candidatoId, prioridad, null);
            
            logger.info("Voto {} encolado para reintento con prioridad: {}", votoId, prioridad);
            
        } catch (Exception e) {
            logger.error("Error encolando voto para reintento {}: {}", votoId, e.getMessage());
        }
    }
    
    /**
     * Obtiene el ID de la estación desde el contexto Ice.
     */
    private String obtenerEstacionIdDelContexto(Current current) {
        String estacionId = "DESCONOCIDA";
        try {
            if (current.ctx != null && current.ctx.containsKey("estacionId")) {
                estacionId = current.ctx.get("estacionId");
            }
        } catch (Exception e) {
            logger.debug("No se pudo obtener estacionId del contexto: {}", e.getMessage());
        }
        return estacionId;
    }
    
    /**
     * Crea los datos del voto en formato JSON.
     */
    private String crearDatosVoto(String votoId, String candidatoId, String estacionId, String hash) {
        return String.format(
            "{\"votoId\":\"%s\",\"candidatoId\":\"%s\",\"estacionId\":\"%s\",\"hash\":\"%s\",\"timestamp\":\"%s\"}",
            votoId, candidatoId, estacionId, hash, java.time.Instant.now().toString()
        );
    }
    
    // === MÉTODOS PARA MÉTRICAS Y MONITOREO ===
    
    public long getTotalVotosRecibidos() {
        return totalVotosRecibidos;
    }
    
    public long getTotalVotosTransmitidos() {
        return totalVotosTransmitidos;
    }
    
    public long getTotalVotosConfirmados() {
        return totalVotosConfirmados;
    }
    
    public long getTotalErrores() {
        return totalErrores;
    }
    
    /**
     * Obtiene el estado actual del BrokerManager.
     */
    public String getEstadoSistema() {
        return String.format(
            "BrokerManager Estado: Recibidos=%d, Transmitidos=%d, Confirmados=%d, Errores=%d",
            totalVotosRecibidos, totalVotosTransmitidos, totalVotosConfirmados, totalErrores
        );
    }
    
    /**
     * Finaliza el BrokerManager y libera recursos.
     */
    public void shutdown() {
        logger.info("Finalizando BrokerManager...");
        if (asyncExecutor != null && !asyncExecutor.isShutdown()) {
            asyncExecutor.shutdown();
        }
        logger.info("BrokerManager finalizado");
    }
} 