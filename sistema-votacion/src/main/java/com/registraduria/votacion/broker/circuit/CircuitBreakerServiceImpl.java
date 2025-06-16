package com.registraduria.votacion.broker.circuit;

import Votacion.CircuitBreakerService;

import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CircuitBreakerService - Implementa el patrón Circuit Breaker para tolerancia a fallos
 * en la comunicación con servicios externos (ServidorCentral).
 * 
 * Estados del Circuit Breaker:
 * - CERRADO: Funcionamiento normal
 * - ABIERTO: Fallas detectadas, no se permiten llamadas
 * - MEDIO_ABIERTO: Probando si el servicio se ha recuperado
 */
public class CircuitBreakerServiceImpl implements CircuitBreakerService {
    private static final Logger logger = LoggerFactory.getLogger(CircuitBreakerServiceImpl.class);
    
    // Estados del Circuit Breaker
    public enum EstadoCircuitBreaker {
        CERRADO, ABIERTO, MEDIO_ABIERTO
    }
    
    // Configuración por defecto
    private static final int DEFAULT_FAILURE_THRESHOLD = 5;
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_SUCCESS_THRESHOLD = 3;
    
    // Configuración del Circuit Breaker
    private final int failureThreshold;
    private final long timeoutSeconds;
    private final int successThreshold;
    
    // Estados por destino
    private final ConcurrentHashMap<String, CircuitBreakerState> circuitBreakers;
    
    /**
     * Constructor del CircuitBreakerService.
     * 
     * @param props Propiedades de configuración
     */
    public CircuitBreakerServiceImpl(Properties props) {
        this.failureThreshold = Integer.parseInt(
            props.getProperty("CircuitBreaker.FailureThreshold", String.valueOf(DEFAULT_FAILURE_THRESHOLD)));
        this.timeoutSeconds = Long.parseLong(
            props.getProperty("CircuitBreaker.TimeoutSeconds", String.valueOf(DEFAULT_TIMEOUT_SECONDS)));
        this.successThreshold = Integer.parseInt(
            props.getProperty("CircuitBreaker.SuccessThreshold", String.valueOf(DEFAULT_SUCCESS_THRESHOLD)));
        
        this.circuitBreakers = new ConcurrentHashMap<>();
        
        logger.info("CircuitBreakerService inicializado");
        logger.info("Failure Threshold: {}, Timeout: {}s, Success Threshold: {}", 
            failureThreshold, timeoutSeconds, successThreshold);
    }
    
    /**
     * Verifica si el circuit breaker está abierto para un destino específico.
     * 
     * @param destino Nombre del destino (ej: "ServidorCentral")
     * @param current Contexto de Ice
     * @return true si el circuit breaker está abierto
     */
    @Override
    public boolean verificarCircuitoAbierto(String destino, Current current) {
        logger.debug("Verificando estado de circuit breaker para: {}", destino);
        
        CircuitBreakerState state = obtenerOCrearEstado(destino);
        
        synchronized (state) {
            EstadoCircuitBreaker estadoActual = state.estado;
            long tiempoActual = Instant.now().getEpochSecond();
            
            switch (estadoActual) {
                case CERRADO:
                    logger.debug("Circuit breaker CERRADO para {}", destino);
                    return false;
                    
                case ABIERTO:
                    // Verificar si es tiempo de intentar medio abierto
                    if (tiempoActual - state.ultimoCambioEstado >= timeoutSeconds) {
                        logger.info("Cambiando circuit breaker de ABIERTO a MEDIO_ABIERTO para {}", destino);
                        state.estado = EstadoCircuitBreaker.MEDIO_ABIERTO;
                        state.ultimoCambioEstado = tiempoActual;
                        state.exitosConsecutivos.set(0);
                        return false; // Permitir una llamada de prueba
                    }
                    logger.debug("Circuit breaker ABIERTO para {} (tiempo restante: {}s)", 
                        destino, timeoutSeconds - (tiempoActual - state.ultimoCambioEstado));
                    return true;
                    
                case MEDIO_ABIERTO:
                    logger.debug("Circuit breaker MEDIO_ABIERTO para {}", destino);
                    return false; // Permitir llamadas de prueba
                    
                default:
                    return false;
            }
        }
    }
    
    /**
     * Registra un fallo en la comunicación con el destino.
     * 
     * @param destino Nombre del destino
     * @param current Contexto de Ice
     */
    @Override
    public void registrarFallo(String destino, Current current) {
        logger.warn("Registrando fallo para destino: {}", destino);
        
        CircuitBreakerState state = obtenerOCrearEstado(destino);
        
        synchronized (state) {
            state.fallosConsecutivos.incrementAndGet();
            state.totalFallos.incrementAndGet();
            state.ultimoFallo = Instant.now().getEpochSecond();
            
            // Resetear éxitos consecutivos
            state.exitosConsecutivos.set(0);
            
            EstadoCircuitBreaker estadoAnterior = state.estado;
            
            // Evaluar cambio de estado
            switch (state.estado) {
                case CERRADO:
                    if (state.fallosConsecutivos.get() >= failureThreshold) {
                        logger.error("Circuit breaker cambiando de CERRADO a ABIERTO para {} (fallos: {})", 
                            destino, state.fallosConsecutivos.get());
                        state.estado = EstadoCircuitBreaker.ABIERTO;
                        state.ultimoCambioEstado = Instant.now().getEpochSecond();
                    }
                    break;
                    
                case MEDIO_ABIERTO:
                    logger.error("Circuit breaker cambiando de MEDIO_ABIERTO a ABIERTO para {} (fallo en prueba)", 
                        destino);
                    state.estado = EstadoCircuitBreaker.ABIERTO;
                    state.ultimoCambioEstado = Instant.now().getEpochSecond();
                    break;
                    
                case ABIERTO:
                    // Ya está abierto, solo incrementar contador
                    logger.debug("Circuit breaker ya ABIERTO para {}, incrementando contador de fallos", destino);
                    break;
            }
            
            if (estadoAnterior != state.estado) {
                logger.info("Circuit breaker para {} cambió de {} a {}", 
                    destino, estadoAnterior, state.estado);
            }
        }
    }
    
    /**
     * Registra un éxito en la comunicación con el destino.
     * 
     * @param destino Nombre del destino
     * @param current Contexto de Ice
     */
    @Override
    public void registrarExito(String destino, Current current) {
        logger.debug("Registrando éxito para destino: {}", destino);
        
        CircuitBreakerState state = obtenerOCrearEstado(destino);
        
        synchronized (state) {
            state.exitosConsecutivos.incrementAndGet();
            state.totalExitos.incrementAndGet();
            state.ultimoExito = Instant.now().getEpochSecond();
            
            // Resetear fallos consecutivos
            state.fallosConsecutivos.set(0);
            
            EstadoCircuitBreaker estadoAnterior = state.estado;
            
            // Evaluar cambio de estado
            switch (state.estado) {
                case CERRADO:
                    // Ya está cerrado, mantener estado
                    logger.debug("Circuit breaker CERRADO para {}, éxito registrado", destino);
                    break;
                    
                case MEDIO_ABIERTO:
                    if (state.exitosConsecutivos.get() >= successThreshold) {
                        logger.info("Circuit breaker cambiando de MEDIO_ABIERTO a CERRADO para {} (éxitos: {})", 
                            destino, state.exitosConsecutivos.get());
                        state.estado = EstadoCircuitBreaker.CERRADO;
                        state.ultimoCambioEstado = Instant.now().getEpochSecond();
                    } else {
                        logger.debug("Circuit breaker MEDIO_ABIERTO para {}, éxitos consecutivos: {}/{}", 
                            destino, state.exitosConsecutivos.get(), successThreshold);
                    }
                    break;
                    
                case ABIERTO:
                    // No debería pasar, pero registrar el éxito
                    logger.warn("Éxito registrado con circuit breaker ABIERTO para {}", destino);
                    break;
            }
            
            if (estadoAnterior != state.estado) {
                logger.info("Circuit breaker para {} cambió de {} a {}", 
                    destino, estadoAnterior, state.estado);
            }
        }
    }
    
    /**
     * Resetea manualmente el circuit breaker para un destino.
     */
    public void resetearCircuitBreaker(String destino) {
        logger.info("Reseteando circuit breaker manualmente para: {}", destino);
        
        CircuitBreakerState state = obtenerOCrearEstado(destino);
        
        synchronized (state) {
            EstadoCircuitBreaker estadoAnterior = state.estado;
            
            state.estado = EstadoCircuitBreaker.CERRADO;
            state.fallosConsecutivos.set(0);
            state.exitosConsecutivos.set(0);
            state.ultimoCambioEstado = Instant.now().getEpochSecond();
            
            logger.info("Circuit breaker para {} reseteado de {} a CERRADO", destino, estadoAnterior);
        }
    }
    
    /**
     * Obtiene el estado actual del circuit breaker para un destino.
     */
    public String obtenerEstadoCircuitBreaker(String destino) {
        CircuitBreakerState state = circuitBreakers.get(destino);
        if (state == null) {
            return String.format("Destino: %s, Estado: NO_INICIALIZADO", destino);
        }
        
        synchronized (state) {
            return String.format(
                "Destino: %s, Estado: %s, Fallos: %d/%d, Éxitos: %d/%d, Total Fallos: %d, Total Éxitos: %d",
                destino, state.estado, 
                state.fallosConsecutivos.get(), failureThreshold,
                state.exitosConsecutivos.get(), successThreshold,
                state.totalFallos.get(), state.totalExitos.get()
            );
        }
    }
    
    /**
     * Obtiene métricas de todos los circuit breakers.
     */
    public String obtenerMetricasCompletas() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== MÉTRICAS CIRCUIT BREAKER ===\n");
        sb.append(String.format("Configuración: Fallos=%d, Timeout=%ds, Éxitos=%d\n", 
            failureThreshold, timeoutSeconds, successThreshold));
        
        if (circuitBreakers.isEmpty()) {
            sb.append("No hay circuit breakers registrados\n");
        } else {
            for (String destino : circuitBreakers.keySet()) {
                sb.append(obtenerEstadoCircuitBreaker(destino)).append("\n");
            }
        }
        
        return sb.toString();
    }
    
    // === MÉTODOS PRIVADOS ===
    
    /**
     * Obtiene o crea el estado del circuit breaker para un destino.
     */
    private CircuitBreakerState obtenerOCrearEstado(String destino) {
        return circuitBreakers.computeIfAbsent(destino, k -> {
            logger.info("Creando nuevo circuit breaker para destino: {}", destino);
            return new CircuitBreakerState();
        });
    }
    
    // === CLASE INTERNA PARA ESTADO ===
    
    /**
     * Estado interno del circuit breaker para un destino específico.
     */
    private static class CircuitBreakerState {
        volatile EstadoCircuitBreaker estado = EstadoCircuitBreaker.CERRADO;
        final AtomicInteger fallosConsecutivos = new AtomicInteger(0);
        final AtomicInteger exitosConsecutivos = new AtomicInteger(0);
        final AtomicLong totalFallos = new AtomicLong(0);
        final AtomicLong totalExitos = new AtomicLong(0);
        volatile long ultimoCambioEstado = Instant.now().getEpochSecond();
        volatile long ultimoFallo = 0;
        volatile long ultimoExito = 0;
    }
} 