#pragma once

#include "Common.ice"

/**
 * Definiciones ICE para VotosBroker - Microservorio intermedio robusto
 * para transmisión confiable de votos entre CentroVotación y ServidorCentral.
 */
module Votacion {

    // =================================================================
    // ENUMERACIONES Y ESTRUCTURAS ESPECÍFICAS DEL BROKER
    // =================================================================
    
    /**
     * Prioridades para los votos en la cola del broker.
     */
    enum PrioridadVoto {
        CRITICA,    // Máxima prioridad - votos críticos del sistema
        ALTA,       // Alta prioridad - reintentos, recuperación
        NORMAL,     // Prioridad normal - operación estándar
        BAJA        // Baja prioridad - mantenimiento, limpieza
    };
    
    /**
     * Estructura para representar un voto pendiente en el broker.
     */
    struct VotoPendiente {
        string votoId;
        string candidatoId;
        string estacionId;
        string hash;
        string timestamp;
        int prioridad;
        int intentosEnvio;
    };

    // =================================================================
    // EXCEPCIONES ESPECÍFICAS DEL BROKER
    // =================================================================
    
    /**
     * Excepción lanzada cuando la cola del broker está llena.
     */
    exception QueueFullException {
        string razon;
    };
    
    /**
     * Excepción lanzada cuando el Circuit Breaker está abierto.
     */
    exception CircuitBreakerException {
        string destino;
        string razon;
    };

    // =================================================================
    // INTERFACES PRINCIPALES DEL BROKER
    // =================================================================
    
    /**
     * BrokerManager - Interfaz principal expuesta al exterior.
     * Recibe peticiones de transmisión y confirmación de votos.
     */
    interface BrokerManager {
        /**
         * Transmite un voto al ServidorCentral de forma asíncrona y confiable.
         * Provisto a MotorEmisionVotos (remoto).
         */
        void transmitirVotoServidor(string votoId, string candidatoId, string hash)
            throws ErrorPersistenciaException, CircuitBreakerException;
            
        /**
         * Confirma que un voto fue enviado exitosamente al ServidorCentral.
         * Provisto a MotorEmisionVotos (remoto).
         */
        void confirmarEnvioServidor(string votoId, EstadoVoto estado)
            throws ErrorPersistenciaException;
    };
    
    /**
     * QueueService - Administra las colas de mensajes priorizadas.
     */
    interface QueueService {
        /**
         * Encola un voto con la prioridad especificada.
         */
        void encolarVoto(string votoId, string candidatoId, PrioridadVoto prioridad)
            throws QueueFullException, ErrorPersistenciaException;
            
        /**
         * Recupera el siguiente voto de la cola con mayor prioridad.
         */
        VotoPendiente recuperarSiguienteVoto()
            throws ErrorPersistenciaException;
    };
    
    /**
     * PersistenceManager - Persistencia de mensajes/votos pendientes y su estado.
     */
    interface PersistenceManager {
        /**
         * Almacena un voto pendiente de forma transaccional.
         */
        void almacenarVotoPendiente(string votoId, Object datos)
            throws ErrorPersistenciaException;
            
        /**
         * Recupera todos los votos pendientes de envío.
         */
        string recuperarVotosPendientesEnvio()
            throws ErrorPersistenciaException;
            
        /**
         * Marca un voto como enviado exitosamente.
         */
        void marcarVotoEnviado(string votoId)
            throws ErrorPersistenciaException;
    };
    
    /**
     * CircuitBreakerService - Implementa patrón Circuit Breaker.
     */
    interface CircuitBreakerService {
        /**
         * Verifica si el circuit breaker está abierto para un destino.
         */
        bool verificarCircuitoAbierto(string destino);
        
        /**
         * Registra un fallo en la comunicación con el destino.
         */
        void registrarFallo(string destino);
        
        /**
         * Registra un éxito en la comunicación con el destino.
         */
        void registrarExito(string destino);
    };
    
    /**
     * RetryHandler - Política de reintentos ante fallos temporales.
     */
    interface RetryHandler {
        /**
         * Procesa votos pendientes de forma asíncrona.
         */
        void procesarVotosPendientes();
        
        /**
         * Programa un reintento para un voto específico.
         */
        void programarReintento(string votoId, int intentosPrevios);
    };

    // =================================================================
    // INTERFACES DE MONITOREO Y ADMINISTRACIÓN
    // =================================================================
    
    /**
     * MonitorBroker - Interface para monitoreo del estado del broker.
     */
    interface MonitorBroker {
        /**
         * Obtiene métricas generales del broker.
         */
        string obtenerMetricas();
        
        /**
         * Obtiene estado de las colas.
         */
        string obtenerEstadoColas();
        
        /**
         * Obtiene estado del circuit breaker.
         */
        string obtenerEstadoCircuitBreaker();
        
        /**
         * Fuerza el procesamiento de votos pendientes.
         */
        void forzarProcesamientoPendientes();
    };
    
    /**
     * AdminBroker - Interface administrativa del broker.
     */
    interface AdminBroker {
        /**
         * Resetea el circuit breaker para un destino.
         */
        void resetearCircuitBreaker(string destino);
        
        /**
         * Limpia votos antiguos de las colas.
         */
        void limpiarVotosAntiguos(int diasAntiguedad);
        
        /**
         * Realiza backup de datos críticos.
         */
        void realizarBackup();
        
        /**
         * Obtiene configuración actual del broker.
         */
        string obtenerConfiguracion();
    };
    
};
