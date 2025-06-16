package com.registraduria.votacion.broker.retry;

import Votacion.ControllerCentralPrx;
import Votacion.EstadoVoto;
import Votacion.PrioridadVoto;
import Votacion.RetryHandler;
import Votacion.VotoPendiente;

import com.registraduria.votacion.broker.circuit.CircuitBreakerServiceImpl;
import com.registraduria.votacion.broker.model.DatosBroker;
import com.registraduria.votacion.broker.persistence.PersistenceManagerImpl;
import com.registraduria.votacion.broker.queue.QueueServiceImpl;

import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RetryHandler - Maneja la política de reintentos ante fallos temporales o circuito abierto.
 * 
 * Implementa backoff exponencial y coordina con CircuitBreakerService para
 * optimizar la transmisión de votos pendientes.
 */
public class RetryHandlerImpl implements RetryHandler {
    private static final Logger logger = LoggerFactory.getLogger(RetryHandlerImpl.class);
    
    // Configuración por defecto
    private static final int DEFAULT_MAX_RETRY_ATTEMPTS = 10;
    private static final long DEFAULT_INITIAL_DELAY_SECONDS = 30;
    private static final long DEFAULT_PROCESSING_INTERVAL_SECONDS = 60;
    private static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;
    
    // Componentes dependientes
    private final QueueServiceImpl queueService;
    private final PersistenceManagerImpl persistenceManager;
    private final CircuitBreakerServiceImpl circuitBreakerService;
    private final ControllerCentralPrx controllerCentralPrx;
    private final ScheduledExecutorService scheduledExecutor;
    
    // Configuración
    private final int maxRetryAttempts;
    private final long initialDelaySeconds;
    private final long processingIntervalSeconds;
    private final double backoffMultiplier;
    
    // Control de procesamiento
    private final AtomicBoolean processingActive = new AtomicBoolean(false);
    private ScheduledFuture<?> processingTask;
    
    // Métricas
    private volatile long totalVotosProcesados = 0;
    private volatile long totalVotosExitosos = 0;
    private volatile long totalVotosFallidos = 0;
    private volatile long totalReintentos = 0;
    
    /**
     * Constructor del RetryHandler.
     */
    public RetryHandlerImpl(QueueServiceImpl queueService,
                          PersistenceManagerImpl persistenceManager,
                          CircuitBreakerServiceImpl circuitBreakerService,
                          ControllerCentralPrx controllerCentralPrx,
                          ScheduledExecutorService scheduledExecutor,
                          Properties props) {
        
        this.queueService = queueService;
        this.persistenceManager = persistenceManager;
        this.circuitBreakerService = circuitBreakerService;
        this.controllerCentralPrx = controllerCentralPrx;
        this.scheduledExecutor = scheduledExecutor;
        
        // Cargar configuración
        this.maxRetryAttempts = Integer.parseInt(
            props.getProperty("RetryHandler.MaxRetryAttempts", String.valueOf(DEFAULT_MAX_RETRY_ATTEMPTS)));
        this.initialDelaySeconds = Long.parseLong(
            props.getProperty("RetryHandler.InitialDelaySeconds", String.valueOf(DEFAULT_INITIAL_DELAY_SECONDS)));
        this.processingIntervalSeconds = Long.parseLong(
            props.getProperty("RetryHandler.ProcessingIntervalSeconds", String.valueOf(DEFAULT_PROCESSING_INTERVAL_SECONDS)));
        this.backoffMultiplier = Double.parseDouble(
            props.getProperty("RetryHandler.BackoffMultiplier", String.valueOf(DEFAULT_BACKOFF_MULTIPLIER)));
        
        logger.info("RetryHandler inicializado");
        logger.info("Max reintentos: {}, Delay inicial: {}s, Intervalo procesamiento: {}s, Multiplicador backoff: {}", 
            maxRetryAttempts, initialDelaySeconds, processingIntervalSeconds, backoffMultiplier);
    }
    
    /**
     * Procesa votos pendientes de forma asíncrona.
     * 
     * @param current Contexto de Ice
     */
    @Override
    public void procesarVotosPendientes(Current current) {
        logger.info("=== INICIANDO PROCESAMIENTO DE VOTOS PENDIENTES ===");
        
        if (!processingActive.compareAndSet(false, true)) {
            logger.warn("Procesamiento ya está activo, ignorando solicitud");
            return;
        }
        
        try {
            // Verificar estado del Circuit Breaker
            if (circuitBreakerService.verificarCircuitoAbierto("ServidorCentral", null)) {
                logger.info("Circuit Breaker ABIERTO. Saltando procesamiento de reintentos");
                return;
            }
            
            // Obtener votos pendientes desde persistencia
            String votosPendientesStr = persistenceManager.recuperarVotosPendientesEnvio(null);
            
            if (votosPendientesStr == null || votosPendientesStr.trim().isEmpty()) {
                logger.info("No hay votos pendientes para procesar");
                return;
            }
            
            String[] votosPendientes = votosPendientesStr.split(",");
            logger.info("Procesando {} votos pendientes", votosPendientes.length);
            
            int votosProcessados = 0;
            int votosExitosos = 0;
            int votosFallidos = 0;
            
            for (String votoId : votosPendientes) {
                if (votoId.trim().isEmpty()) continue;
                
                try {
                    boolean exito = procesarVotoIndividual(votoId.trim());
                    votosProcessados++;
                    
                    if (exito) {
                        votosExitosos++;
                    } else {
                        votosFallidos++;
                    }
                    
                    // Pausa entre procesamientos para no sobrecargar
                    Thread.sleep(100);
                    
                } catch (Exception e) {
                    logger.error("Error procesando voto {}: {}", votoId, e.getMessage());
                    votosFallidos++;
                }
            }
            
            // Actualizar métricas
            totalVotosProcesados += votosProcessados;
            totalVotosExitosos += votosExitosos;
            totalVotosFallidos += votosFallidos;
            
            logger.info("Procesamiento completado: Procesados={}, Exitosos={}, Fallidos={}", 
                votosProcessados, votosExitosos, votosFallidos);
                
        } catch (Exception e) {
            logger.error("Error en procesamiento de votos pendientes: {}", e.getMessage());
        } finally {
            processingActive.set(false);
        }
    }
    
    /**
     * Programa un reintento para un voto específico.
     * 
     * @param votoId ID del voto
     * @param intentosPrevios Número de intentos previos
     * @param current Contexto de Ice
     */
    @Override
    public void programarReintento(String votoId, int intentosPrevios, Current current) {
        logger.info("Programando reintento para voto: {} (intentos previos: {})", votoId, intentosPrevios);
        
        // Verificar si aún no se han agotado los reintentos
        if (intentosPrevios >= maxRetryAttempts) {
            logger.error("Voto {} ha agotado máximo de reintentos ({}), marcando como fallido", 
                votoId, maxRetryAttempts);
            manejarVotoFallidoDefinitivamente(votoId);
            return;
        }
        
        // Calcular delay con backoff exponencial
        long delaySeconds = calcularDelayBackoff(intentosPrevios);
        
        // Programar ejecución del reintento
        scheduledExecutor.schedule(() -> {
            try {
                logger.info("Ejecutando reintento programado para voto: {}", votoId);
                boolean exito = procesarVotoIndividual(votoId);
                
                if (!exito) {
                    // Si falla, programar siguiente reintento
                    programarReintento(votoId, intentosPrevios + 1, null);
                }
                
            } catch (Exception e) {
                logger.error("Error en reintento programado para voto {}: {}", votoId, e.getMessage());
                programarReintento(votoId, intentosPrevios + 1, null);
            }
        }, delaySeconds, TimeUnit.SECONDS);
        
        totalReintentos++;
        logger.debug("Reintento programado para voto {} en {} segundos", votoId, delaySeconds);
    }
    
    /**
     * Inicia el procesamiento automático periódico.
     */
    public void iniciarProcesamiento() {
        logger.info("Iniciando procesamiento automático de votos pendientes");
        
        processingTask = scheduledExecutor.scheduleWithFixedDelay(() -> {
            try {
                // Solo procesar si hay votos pendientes
                if (queueService.tieneVotosPendientes()) {
                    logger.debug("Ejecutando procesamiento automático periódico");
                    procesarVotosPendientes(null);
                }
            } catch (Exception e) {
                logger.error("Error en procesamiento automático: {}", e.getMessage());
            }
        }, initialDelaySeconds, processingIntervalSeconds, TimeUnit.SECONDS);
        
        logger.info("Procesamiento automático iniciado con intervalo de {} segundos", processingIntervalSeconds);
    }
    
    /**
     * Detiene el procesamiento automático.
     */
    public void detenerProcesamiento() {
        logger.info("Deteniendo procesamiento automático");
        
        if (processingTask != null && !processingTask.isCancelled()) {
            processingTask.cancel(false);
            logger.info("Tarea de procesamiento automático cancelada");
        }
    }
    
    /**
     * Obtiene métricas del RetryHandler.
     */
    public String obtenerMetricas() {
        return String.format(
            "RetryHandler - Procesados: %d, Exitosos: %d, Fallidos: %d, Reintentos: %d, Activo: %s",
            totalVotosProcesados, totalVotosExitosos, totalVotosFallidos, 
            totalReintentos, processingActive.get()
        );
    }
    
    // === MÉTODOS PRIVADOS ===
    
    /**
     * Procesa un voto individual.
     */
    private boolean procesarVotoIndividual(String votoId) {
        try {
            logger.debug("Procesando voto individual: {}", votoId);
            
            // Verificar estado del Circuit Breaker
            if (circuitBreakerService.verificarCircuitoAbierto("ServidorCentral", null)) {
                logger.debug("Circuit Breaker abierto, posponiendo procesamiento de voto: {}", votoId);
                
                // Encolar para reintento con delay
                queueService.encolarVotoParaReintento(votoId, "RECUPERADO", PrioridadVoto.ALTA, 0);
                return false;
            }
            
            // Obtener datos del voto desde persistencia
            DatosBroker datosVoto = persistenceManager.obtenerDatosVoto(votoId);
            if (datosVoto == null) {
                logger.warn("No se encontraron datos para voto: {}", votoId);
                return false;
            }
            
            // Extraer información del voto (desde JSON)
            String[] datosArray = extraerDatosDeVoto(datosVoto.datos);
            if (datosArray.length < 4) {
                logger.error("Datos de voto incompletos para {}: {}", votoId, datosVoto.datos);
                return false;
            }
            
            String candidatoId = datosArray[1];
            String estacionId = datosArray[2];
            String hash = datosArray[3];
            
            // Transmitir al ServidorCentral
            controllerCentralPrx.recibirVotoDesdeEstacion(votoId, candidatoId, estacionId, hash);
            
            // Confirmar recepción
            controllerCentralPrx.confirmarRecepcionCentral(votoId, EstadoVoto.RECIBIDO);
            
            // Marcar como enviado en persistencia
            persistenceManager.marcarVotoEnviado(votoId, null);
            
            // Registrar éxito en Circuit Breaker
            circuitBreakerService.registrarExito("ServidorCentral", null);
            
            totalVotosExitosos++;
            
            logger.info("Voto {} reintentado exitosamente", votoId);
            
            return true;
            
        } catch (Exception e) {
            totalVotosFallidos++;
            
            // Registrar fallo en Circuit Breaker
            circuitBreakerService.registrarFallo("ServidorCentral", null);
            
            logger.error("Error procesando voto {}: {}", votoId, e.getMessage());
            
            return false;
        }
    }
    
    /**
     * Extrae datos del voto desde formato JSON simple.
     */
    private String[] extraerDatosDeVoto(String datosJson) {
        try {
            // Parseo simple de JSON: {"votoId":"V1","candidatoId":"C1","estacionId":"E1","hash":"H1"}
            String contenido = datosJson.replace("{", "").replace("}", "").replace("\"", "");
            String[] pares = contenido.split(",");
            String[] valores = new String[4];
            
            for (String par : pares) {
                String[] partesPar = par.split(":");
                if (partesPar.length == 2) {
                    String clave = partesPar[0].trim();
                    String valor = partesPar[1].trim();
                    
                    switch (clave) {
                        case "votoId": valores[0] = valor; break;
                        case "candidatoId": valores[1] = valor; break;
                        case "estacionId": valores[2] = valor; break;
                        case "hash": valores[3] = valor; break;
                    }
                }
            }
            
            return valores;
            
        } catch (Exception e) {
            logger.error("Error extrayendo datos de voto: {}", e.getMessage());
            return new String[0];
        }
    }
    
    /**
     * Calcula el delay con backoff exponencial.
     */
    private long calcularDelayBackoff(int intentos) {
        long delay = (long) (initialDelaySeconds * Math.pow(backoffMultiplier, intentos));
        
        // Límite máximo de 10 minutos
        long maxDelay = 600;
        return Math.min(delay, maxDelay);
    }
    
    /**
     * Maneja un voto que ha fallado definitivamente.
     */
    private void manejarVotoFallidoDefinitivamente(String votoId) {
        try {
            logger.error("Voto {} fallido definitivamente, moviendo a logs de error", votoId);
            
            // Marcar como fallido en logs
            // (En una implementación real podríamos moverlo a una cola de errores)
            
        } catch (Exception e) {
            logger.error("Error manejando voto fallido definitivamente {}: {}", votoId, e.getMessage());
        }
    }
    
    /**
     * Finaliza el RetryHandler y libera recursos.
     */
    public void shutdown() {
        logger.info("Finalizando RetryHandler...");
        
        detenerProcesamiento();
        
        logger.info("RetryHandler finalizado");
        logger.info("Métricas finales: {}", obtenerMetricas());
    }
} 