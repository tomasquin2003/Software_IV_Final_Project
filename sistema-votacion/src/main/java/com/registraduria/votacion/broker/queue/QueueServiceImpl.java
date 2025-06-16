package com.registraduria.votacion.broker.queue;

import Votacion.ErrorPersistenciaException;
import Votacion.PrioridadVoto;
import Votacion.QueueFullException;
import Votacion.QueueService;
import Votacion.VotoPendiente;

import com.registraduria.votacion.broker.persistence.PersistenceManagerImpl;

import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * QueueService - Administra las colas de mensajes priorizadas para la transmisión
 * confiable de votos al ServidorCentral.
 * 
 * Implementa múltiples colas con diferentes prioridades y mecanismos de retry.
 */
public class QueueServiceImpl implements QueueService {
    private static final Logger logger = LoggerFactory.getLogger(QueueServiceImpl.class);
    
    // Configuración de colas
    private static final int MAX_QUEUE_SIZE = 10000;
    private static final int DELAY_RETRY_SECONDS = 30;
    
    // Colas por prioridad
    private final PriorityBlockingQueue<VotoPendienteConPrioridad> colaPrincipal;
    private final DelayQueue<VotoPendienteConDelay> colaReintento;
    private final ConcurrentHashMap<String, VotoPendienteConPrioridad> votosEnCola;
    
    // Métricas
    private volatile long totalVotosEncolados = 0;
    private volatile long totalVotosRecuperados = 0;
    private volatile long colaRechazada = 0;
    
    // Persistencia
    private final PersistenceManagerImpl persistenceManager;
    
    // Sincronización
    private final ReentrantLock lockCola = new ReentrantLock();
    
    /**
     * Constructor del QueueService.
     */
    public QueueServiceImpl(PersistenceManagerImpl persistenceManager) {
        this.persistenceManager = persistenceManager;
        this.colaPrincipal = new PriorityBlockingQueue<>(1000);
        this.colaReintento = new DelayQueue<>();
        this.votosEnCola = new ConcurrentHashMap<>();
        
        logger.info("QueueService inicializado con capacidad máxima: {}", MAX_QUEUE_SIZE);
        
        // Cargar votos pendientes desde persistencia al inicio
        cargarVotosPendientesDeDB();
        
        // Iniciar thread para procesar votos con delay
        iniciarProcesadorColaReintento();
    }
    
    /**
     * Encola un voto con la prioridad especificada.
     * 
     * @param votoId ID único del voto
     * @param candidatoId ID del candidato
     * @param prioridad Prioridad del voto
     * @param current Contexto de Ice
     * @throws QueueFullException Si la cola está llena
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public void encolarVoto(String votoId, String candidatoId, PrioridadVoto prioridad, Current current) 
            throws QueueFullException, ErrorPersistenciaException {
        
        logger.debug("Encolando voto: {} con prioridad: {}", votoId, prioridad);
        
        lockCola.lock();
        try {
            // Verificar capacidad de la cola
            if (votosEnCola.size() >= MAX_QUEUE_SIZE) {
                colaRechazada++;
                throw new QueueFullException("Cola llena. Capacidad máxima alcanzada: " + MAX_QUEUE_SIZE);
            }
            
            // Verificar si el voto ya está en cola
            if (votosEnCola.containsKey(votoId)) {
                logger.warn("Voto {} ya está en cola, actualizando prioridad a: {}", votoId, prioridad);
                
                // Remover el voto existente y agregar con nueva prioridad
                VotoPendienteConPrioridad votoExistente = votosEnCola.get(votoId);
                colaPrincipal.remove(votoExistente);
            }
            
            // Crear nuevo voto pendiente
            VotoPendienteConPrioridad votoPendiente = new VotoPendienteConPrioridad(
                votoId, candidatoId, prioridad, 0, Instant.now().toString()
            );
            
            // Agregar a la cola principal
            colaPrincipal.offer(votoPendiente);
            votosEnCola.put(votoId, votoPendiente);
            
            totalVotosEncolados++;
            logger.info("Voto {} encolado exitosamente. Total en cola: {}", votoId, votosEnCola.size());
            
        } finally {
            lockCola.unlock();
        }
    }
    
    /**
     * Recupera el siguiente voto de la cola con mayor prioridad.
     * 
     * @param current Contexto de Ice
     * @return El siguiente voto pendiente para procesar
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public VotoPendiente recuperarSiguienteVoto(Current current) throws ErrorPersistenciaException {
        
        lockCola.lock();
        try {
            // Intentar recuperar de cola principal primero
            VotoPendienteConPrioridad voto = colaPrincipal.poll();
            
            if (voto != null) {
                // Remover del mapa de seguimiento
                votosEnCola.remove(voto.votoId);
                totalVotosRecuperados++;
                
                logger.debug("Voto {} recuperado de cola principal. Restantes: {}", 
                    voto.votoId, colaPrincipal.size());
                
                // Convertir a VotoPendiente de Ice
                return convertirAVotoPendiente(voto);
            }
            
            // Si no hay votos en cola principal, retornar null
            logger.debug("No hay votos disponibles en cola principal");
            return null;
            
        } finally {
            lockCola.unlock();
        }
    }
    
    /**
     * Encola un voto para reintento con delay.
     */
    public void encolarVotoParaReintento(String votoId, String candidatoId, PrioridadVoto prioridad, 
                                       int intentosPrevios) {
        
        logger.info("Encolando voto {} para reintento. Intentos previos: {}", votoId, intentosPrevios);
        
        // Calcular delay exponencial (backoff)
        long delaySeconds = calcularDelayExponencial(intentosPrevios);
        
        VotoPendienteConDelay votoConDelay = new VotoPendienteConDelay(
            votoId, candidatoId, prioridad, intentosPrevios + 1, 
            Instant.now().toString(), delaySeconds
        );
        
        colaReintento.offer(votoConDelay);
        logger.debug("Voto {} programado para reintento en {} segundos", votoId, delaySeconds);
    }
    
    /**
     * Verifica si la cola tiene votos pendientes.
     */
    public boolean tieneVotosPendientes() {
        return !colaPrincipal.isEmpty() || !colaReintento.isEmpty();
    }
    
    /**
     * Obtiene el tamaño actual de la cola.
     */
    public int getTamanoCola() {
        return votosEnCola.size() + colaReintento.size();
    }
    
    /**
     * Obtiene estadísticas de la cola.
     */
    public String getEstadisticasCola() {
        return String.format(
            "Cola Principal: %d, Cola Reintento: %d, Total Encolados: %d, Total Recuperados: %d, Rechazados: %d",
            colaPrincipal.size(), colaReintento.size(), totalVotosEncolados, 
            totalVotosRecuperados, colaRechazada
        );
    }
    
    /**
     * Carga votos pendientes desde la base de datos al iniciar.
     */
    private void cargarVotosPendientesDeDB() {
        try {
            logger.info("Cargando votos pendientes desde base de datos...");
            
            String votosPendientes = persistenceManager.recuperarVotosPendientesEnvio(null);
            if (votosPendientes != null && !votosPendientes.trim().isEmpty()) {
                String[] votosArray = votosPendientes.split(",");
                
                for (String votoId : votosArray) {
                    if (!votoId.trim().isEmpty()) {
                        // Encolar con prioridad alta (recuperación)
                        VotoPendienteConPrioridad voto = new VotoPendienteConPrioridad(
                            votoId.trim(), "RECUPERADO", PrioridadVoto.ALTA, 0, 
                            Instant.now().toString()
                        );
                        
                        colaPrincipal.offer(voto);
                        votosEnCola.put(votoId.trim(), voto);
                    }
                }
                
                logger.info("Cargados {} votos pendientes desde base de datos", votosArray.length);
            }
            
        } catch (Exception e) {
            logger.error("Error cargando votos pendientes desde DB: {}", e.getMessage());
        }
    }
    
    /**
     * Inicia el procesador de la cola de reintentos.
     */
    private void iniciarProcesadorColaReintento() {
        Thread procesadorReintento = new Thread(() -> {
            logger.info("Iniciando procesador de cola de reintentos");
            
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // Esperar por votos con delay cumplido
                    VotoPendienteConDelay votoConDelay = colaReintento.take();
                    
                    logger.debug("Voto {} listo para reintento después de delay", votoConDelay.votoId);
                    
                    // Mover de vuelta a cola principal con mayor prioridad
                    lockCola.lock();
                    try {
                        VotoPendienteConPrioridad voto = new VotoPendienteConPrioridad(
                            votoConDelay.votoId, votoConDelay.candidatoId, 
                            PrioridadVoto.ALTA, votoConDelay.intentosEnvio, 
                            votoConDelay.timestamp
                        );
                        
                        colaPrincipal.offer(voto);
                        votosEnCola.put(voto.votoId, voto);
                        
                        logger.info("Voto {} movido de cola reintento a cola principal", voto.votoId);
                        
                    } finally {
                        lockCola.unlock();
                    }
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Error en procesador de cola reintento: {}", e.getMessage());
                }
            }
            
            logger.info("Procesador de cola de reintentos finalizado");
        });
        
        procesadorReintento.setDaemon(true);
        procesadorReintento.setName("QueueRetryProcessor");
        procesadorReintento.start();
    }
    
    /**
     * Calcula el delay exponencial para reintentos.
     */
    private long calcularDelayExponencial(int intentos) {
        // Backoff exponencial: 30s, 60s, 120s, 240s, max 300s
        long delay = DELAY_RETRY_SECONDS * (long) Math.pow(2, Math.min(intentos, 4));
        return Math.min(delay, 300); // Máximo 5 minutos
    }
    
    /**
     * Convierte VotoPendienteConPrioridad a VotoPendiente de Ice.
     */
    private VotoPendiente convertirAVotoPendiente(VotoPendienteConPrioridad voto) {
        VotoPendiente votoPendiente = new VotoPendiente();
        votoPendiente.votoId = voto.votoId;
        votoPendiente.candidatoId = voto.candidatoId;
        votoPendiente.estacionId = "BROKER"; // Estación fija para broker
        votoPendiente.hash = ""; // Hash será obtenido de persistencia
        votoPendiente.timestamp = voto.timestamp;
        votoPendiente.prioridad = obtenerPrioridadNumerica(voto.prioridad);
        votoPendiente.intentosEnvio = voto.intentosEnvio;
        
        return votoPendiente;
    }
    
    /**
     * Obtiene valor numérico de prioridad.
     */
    private int obtenerPrioridadNumerica(PrioridadVoto prioridad) {
        switch (prioridad) {
            case CRITICA: return 1;
            case ALTA: return 2;
            case NORMAL: return 3;
            case BAJA: return 4;
            default: return 3;
        }
    }
    
    // === CLASES INTERNAS ===
    
    /**
     * Voto pendiente con prioridad para cola principal.
     */
    private static class VotoPendienteConPrioridad implements Comparable<VotoPendienteConPrioridad> {
        final String votoId;
        final String candidatoId;
        final PrioridadVoto prioridad;
        final int intentosEnvio;
        final String timestamp;
        
        public VotoPendienteConPrioridad(String votoId, String candidatoId, PrioridadVoto prioridad, 
                                       int intentosEnvio, String timestamp) {
            this.votoId = votoId;
            this.candidatoId = candidatoId;
            this.prioridad = prioridad;
            this.intentosEnvio = intentosEnvio;
            this.timestamp = timestamp;
        }
        
        @Override
        public int compareTo(VotoPendienteConPrioridad other) {
            // Prioridad más alta primero (CRITICA=1, ALTA=2, etc.)
            int prioridadComparacion = Integer.compare(
                obtenerValorPrioridad(this.prioridad),
                obtenerValorPrioridad(other.prioridad)
            );
            
            if (prioridadComparacion != 0) {
                return prioridadComparacion;
            }
            
            // Si misma prioridad, más antiguo primero
            return this.timestamp.compareTo(other.timestamp);
        }
        
        private int obtenerValorPrioridad(PrioridadVoto prioridad) {
            switch (prioridad) {
                case CRITICA: return 1;
                case ALTA: return 2;
                case NORMAL: return 3;
                case BAJA: return 4;
                default: return 3;
            }
        }
    }
    
    /**
     * Voto pendiente con delay para cola de reintentos.
     */
    private static class VotoPendienteConDelay implements Delayed {
        final String votoId;
        final String candidatoId;
        final PrioridadVoto prioridad;
        final int intentosEnvio;
        final String timestamp;
        final long tiempoEjecucion;
        
        public VotoPendienteConDelay(String votoId, String candidatoId, PrioridadVoto prioridad, 
                                   int intentosEnvio, String timestamp, long delaySeconds) {
            this.votoId = votoId;
            this.candidatoId = candidatoId;
            this.prioridad = prioridad;
            this.intentosEnvio = intentosEnvio;
            this.timestamp = timestamp;
            this.tiempoEjecucion = System.currentTimeMillis() + (delaySeconds * 1000);
        }
        
        @Override
        public long getDelay(TimeUnit unit) {
            long diff = tiempoEjecucion - System.currentTimeMillis();
            return unit.convert(diff, TimeUnit.MILLISECONDS);
        }
        
        @Override
        public int compareTo(Delayed other) {
            if (this.tiempoEjecucion < ((VotoPendienteConDelay) other).tiempoEjecucion) {
                return -1;
            }
            if (this.tiempoEjecucion > ((VotoPendienteConDelay) other).tiempoEjecucion) {
                return 1;
            }
            return 0;
        }
    }
} 