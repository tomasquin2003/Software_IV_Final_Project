package com.registraduria.votacion.centro.recepcion;

import Votacion.AlmacenamientoVotosPrx;
import Votacion.ErrorPersistenciaException;
import Votacion.EstadoVoto;
import Votacion.GestorRecepcionVotos;
import Votacion.MotorEmisionVotosPrx;
import Votacion.ValidadorDeVotosPrx;
import Votacion.Voto;
import Votacion.VotoDuplicadoException;

import com.registraduria.votacion.centro.validacion.ValidadorDeVotosImpl;
import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Implementación del servicio GestorRecepcionVotos que recibe los votos de las
 * Estaciones de Votación, los valida, almacena y procesa.
 */
public class GestorRecepcionVotosImpl implements GestorRecepcionVotos {
    private static final Logger logger = LoggerFactory.getLogger(GestorRecepcionVotosImpl.class);
    
    // Proxies para acceder a los servicios necesarios
    private final AlmacenamientoVotosPrx almacenamientoVotos;
    private final ValidadorDeVotosPrx validadorDeVotos;
    private final MotorEmisionVotosPrx motorEmisionVotos;
    
    // Referencia directa al validador para actualizar el registro de votos procesados
    private final ValidadorDeVotosImpl validadorImpl;
    
    // Executor para procesar votos en segundo plano
    private final Executor executor = Executors.newFixedThreadPool(5);
    
    // Scheduler para verificar votos pendientes
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    // Intervalo para verificar votos pendientes (en segundos)
    private static final int INTERVALO_VERIFICACION_PENDIENTES = 60;
    
    /**
     * Constructor que inicializa el gestor con los servicios necesarios.
     * 
     * @param almacenamientoVotos Proxy para el almacenamiento de votos
     * @param validadorDeVotos Proxy para el validador de votos
     * @param motorEmisionVotos Proxy para el motor de emisión de votos
     * @param validadorImpl Referencia directa al validador
     */
    public GestorRecepcionVotosImpl(
            AlmacenamientoVotosPrx almacenamientoVotos,
            ValidadorDeVotosPrx validadorDeVotos,
            MotorEmisionVotosPrx motorEmisionVotos,
            ValidadorDeVotosImpl validadorImpl) {
        
        this.almacenamientoVotos = almacenamientoVotos;
        this.validadorDeVotos = validadorDeVotos;
        this.motorEmisionVotos = motorEmisionVotos;
        this.validadorImpl = validadorImpl;
        
        logger.info("GestorRecepcionVotos inicializado");
        
        // Iniciar tarea programada para verificar votos pendientes
        iniciarTareaVerificacionPendientes();
    }
    
    /**
     * Inicia la tarea programada para verificar y procesar votos pendientes.
     */
    private void iniciarTareaVerificacionPendientes() {
        scheduler.scheduleAtFixedRate(
            this::verificarYProcesarVotosPendientes,
            INTERVALO_VERIFICACION_PENDIENTES,
            INTERVALO_VERIFICACION_PENDIENTES,
            TimeUnit.SECONDS
        );
        
        logger.info("Tarea de verificación de votos pendientes programada cada {} segundos", 
                INTERVALO_VERIFICACION_PENDIENTES);
    }
      /**
     * Recibe un voto de una Estación de Votación.
     * 
     * @param voto Voto recibido
     * @param callback Callback proxy para confirmar la recepción del voto
     * @param current Contexto de la llamada Ice (añadido por Ice a la implementación del sirviente)
     * @throws VotoDuplicadoException si el voto ya ha sido procesado
     * @throws ErrorPersistenciaException si hay un error al almacenar el voto
     */
    @Override
    public void recibirVoto(Voto voto, Votacion.GestorEnvioVotosCallbackPrx callback, Current current) 
            throws VotoDuplicadoException, ErrorPersistenciaException {
        
        logger.info("Recibido voto. ID: {}, Candidato: {}, Estación: {}", 
                voto.votoId, voto.candidatoId, voto.estacionOrigen);
        
        // Verificar si el voto es único
        boolean esUnico = validadorDeVotos.validarVotoUnico(voto.votoId);
        
        if (!esUnico) {
            logger.warn("Voto duplicado detectado. ID: {}", voto.votoId);            // Enviar confirmación de que ya fue procesado (para evitar reenvíos)
            try {
                // CORRECCIÓN: Usar el proxy directamente
                callback.confirmarRecepcionVoto(voto.votoId, EstadoVoto.PROCESADO);
            } catch (Exception e) {
                logger.error("Error al enviar confirmación de voto duplicado", e);
            }
            throw new VotoDuplicadoException(voto.votoId, "Voto ya procesado previamente");
        }
        
        // Registrar voto como recibido
        try {
            almacenamientoVotos.registrarVotoRecibido(voto.votoId, voto.candidatoId, voto.estacionOrigen, EstadoVoto.RECIBIDO);            // Enviar confirmación de recepción
            try {
                // CORRECCIÓN: Usar el proxy directamente
                callback.confirmarRecepcionVoto(voto.votoId, EstadoVoto.RECIBIDO);
                logger.debug("Confirmación de recepción enviada. ID: {}", voto.votoId);
            } catch (Exception e) {
                logger.error("Error al enviar confirmación de recepción", e);
                // Continuar con el procesamiento a pesar del error en la confirmación
            }
            
            // Procesar el voto en segundo plano
            executor.execute(() -> procesarVoto(voto, callback));
            
        } catch (ErrorPersistenciaException e) {
            logger.error("Error al registrar voto recibido", e);
            throw e;
        } catch (Exception e) {
            logger.error("Error inesperado al recibir voto", e);
            throw new ErrorPersistenciaException("Error inesperado al recibir voto: " + e.getMessage());
        }
    }
      /**
     * Procesa un voto validándolo, registrándolo y enviando confirmación.
     * 
     * @param voto Voto a procesar
     * @param callback Callback proxy para enviar confirmación
     */
    private void procesarVoto(Voto voto, Votacion.GestorEnvioVotosCallbackPrx callback) {
        try {
            // Procesar el voto en el motor de emisión
            motorEmisionVotos.procesarVotoValidado(voto.candidatoId);
            logger.info("Voto procesado por el motor de emisión. ID: {}", voto.votoId);
            
            // Marcar voto como procesado en el almacenamiento
            almacenamientoVotos.marcarVotoProcesado(voto.votoId);
            logger.debug("Voto marcado como PROCESADO. ID: {}", voto.votoId);
            
            // Registrar voto como procesado en el validador
            validadorImpl.registrarVotoProcesado(voto.votoId);
            logger.debug("Voto registrado en validador. ID: {}", voto.votoId);
              // Enviar confirmación final de procesamiento
            try {
                // CORRECCIÓN: Usar el proxy directamente
                callback.confirmarRecepcionVoto(voto.votoId, EstadoVoto.PROCESADO);
                logger.info("Confirmación final enviada. ID: {}", voto.votoId);
            } catch (Exception e) {
                logger.error("Error al enviar confirmación final", e);
                // El voto ya está procesado, así que no es crítico si la confirmación falla
            }
            
        } catch (Exception e) {
            logger.error("Error al procesar voto", e);
              // Intentar enviar notificación de error
            try {
                // CORRECCIÓN: Usar el proxy directamente
                callback.confirmarRecepcionVoto(voto.votoId, EstadoVoto.ERROR);
                logger.debug("Notificación de error enviada. ID: {}", voto.votoId);
            } catch (Exception ex) {
                logger.error("Error al enviar notificación de error", ex);
            }
        }
    }
    
    /**
     * Verifica y procesa los votos pendientes.
     */
    // Modificar el método verificarYProcesarVotosPendientes
    private void verificarYProcesarVotosPendientes() {
        logger.info("Verificando votos pendientes para procesar");
        
        try {
            // Verificar si hay votos pendientes
            boolean hayPendientes = almacenamientoVotos.hayVotosPendientes();
            
            if (!hayPendientes) {
                logger.info("No hay votos pendientes para procesar");
                return;
            }
            
            // Obtener los IDs de los votos pendientes
            String idsStr = almacenamientoVotos.obtenerIdsVotosPendientes();
            String[] ids = idsStr.split(",");
            
            if (ids.length == 0 || (ids.length == 1 && ids[0].isEmpty())) {
                logger.info("No hay votos pendientes para procesar");
                return;
            }
            
            logger.info("Encontrados {} votos pendientes para procesar", ids.length);
            
            // Procesar cada voto pendiente
            for (String votoId : ids) {
                if (votoId.isEmpty()) continue;
                
                logger.info("Procesando voto pendiente. ID: {}", votoId);
                Voto voto = almacenamientoVotos.obtenerVotoPendiente(votoId);
                
                try {
                    // Procesar el voto en el motor de emisión
                    motorEmisionVotos.procesarVotoValidado(voto.candidatoId);
                    logger.info("Voto pendiente procesado por el motor de emisión. ID: {}", votoId);
                    
                    // Marcar voto como procesado en el almacenamiento
                    almacenamientoVotos.marcarVotoProcesado(votoId);
                    logger.info("Voto pendiente marcado como PROCESADO. ID: {}", votoId);
                    
                    // Registrar voto como procesado en el validador
                    validadorImpl.registrarVotoProcesado(votoId);
                    logger.debug("Voto pendiente registrado en validador. ID: {}", votoId);
                    
                } catch (Exception e) {
                    logger.error("Error al procesar voto pendiente. ID: " + votoId, e);
                }
            }
            
        } catch (Exception e) {
            logger.error("Error al verificar y procesar votos pendientes", e);
        }
    }
}