package com.registraduria.votacion.estacion.votacion;

import Votacion.AlmacenamientoTransitorioPrx;
import Votacion.ErrorPersistenciaException;
import Votacion.EstadoVoto;
import Votacion.GestorEnvioVotosCallback;
import Votacion.GestorRecepcionVotosPrx;
import Votacion.Voto;
import Votacion.VotoDuplicadoException;
import Votacion.VotoSeq;

import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Implementación del GestorEnvioVotos y GestorEnvioVotosCallback que maneja el envío confiable
 * de votos al Centro de Votación, incluyendo reintentos y confirmaciones.
 */
public class GestorEnvioVotosImpl implements GestorEnvioVotosCallback {
    private static final Logger logger = LoggerFactory.getLogger(GestorEnvioVotosImpl.class);
    
    // Proxy para acceder al servicio de almacenamiento transitorio
    private final AlmacenamientoTransitorioPrx almacenamientoTransitorio;
    
    // Proxy para acceder al servicio de recepción de votos en el Centro
    private final GestorRecepcionVotosPrx gestorRecepcionVotos;
    
    // ID de la estación de votación
    private final String estacionId;
    
    // Servicio para programar reintentos periódicos
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    // Intervalos para reintentos (en segundos)
    private static final int INTERVALO_REINTENTO_INICIAL = 5;
    private static final int INTERVALO_VERIFICACION_PENDIENTES = 30;
    private static final int MAX_REINTENTOS = 10;

    // Adaptador para crear proxies
    private final com.zeroc.Ice.ObjectAdapter adapter;
    // Identity para el callback
    private final com.zeroc.Ice.Identity callbackId;
    
    /**
     * Constructor que inicializa el gestor con las dependencias necesarias.
     * 
     * @param almacenamientoTransitorio Proxy para el servicio de almacenamiento transitorio
     * @param gestorRecepcionVotos Proxy para el servicio de recepción de votos
     * @param estacionId ID de la estación de votación
     */
    public GestorEnvioVotosImpl(
        AlmacenamientoTransitorioPrx almacenamientoTransitorio,
        GestorRecepcionVotosPrx gestorRecepcionVotos,
        String estacionId,
        com.zeroc.Ice.ObjectAdapter adapter,
        com.zeroc.Ice.Identity callbackId) {
    
    this.almacenamientoTransitorio = almacenamientoTransitorio;
    this.gestorRecepcionVotos = gestorRecepcionVotos;
    this.estacionId = estacionId;
    this.adapter = adapter;
    this.callbackId = callbackId;
    
    logger.info("GestorEnvioVotos inicializado. Estación ID: {}", estacionId);
    
    // Iniciar tarea programada para verificar votos pendientes
    iniciarTareaVerificacionPendientes();
    }
    
    /**
     * Inicia la tarea programada para verificar y reenviar votos pendientes.
     */
    private void iniciarTareaVerificacionPendientes() {
        scheduler.scheduleAtFixedRate(
            this::verificarYReenviarVotosPendientes,
            INTERVALO_VERIFICACION_PENDIENTES,
            INTERVALO_VERIFICACION_PENDIENTES,
            TimeUnit.SECONDS
        );
        
        logger.info("Tarea de verificación de votos pendientes programada cada {} segundos", 
                INTERVALO_VERIFICACION_PENDIENTES);
    }
    
    /**
     * Método para que el votante emita su voto.
     * 
     * @param candidatoId ID del candidato elegido
     * @return ID único asignado al voto
     * @throws Exception si hay un error en el proceso
     */
    public String enviarVoto(String candidatoId) throws Exception {
        // Generar ID único para el voto
        String votoId = UUID.randomUUID().toString();
        
        logger.info("Procesando nuevo voto. ID: {}, Candidato: {}", votoId, candidatoId);
        
        try {
            // Almacenar el voto como pendiente
            almacenamientoTransitorio.almacenarVotoTransitorio(votoId, candidatoId, EstadoVoto.PENDIENTE);
            
            // Crear objeto Voto
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            Voto voto = new Voto(votoId, candidatoId, estacionId, timestamp);
            
            // Enviar voto al Centro (primera vez)
            enviarVotoAlCentro(voto);
            
            return votoId;
            
        } catch (Exception e) {
            logger.error("Error al procesar nuevo voto", e);
            throw e;
        }
    }
    
    /**
     * Envía un voto al Centro de Votación.
     * 
     * @param voto Objeto Voto a enviar
     */
    private void enviarVotoAlCentro(Voto voto) {
        logger.info("Enviando voto al Centro. ID: {}", voto.votoId);
        
        try {
            // Obtener un proxy para este objeto como callback
            GestorEnvioVotosCallbackPrx callbackPrx = GestorEnvioVotosCallbackPrx.uncheckedCast(
                adapter.createProxy(callbackId));
            
            // Enviar el voto al Centro usando el proxy para el callback
            gestorRecepcionVotos.recibirVoto(voto, callbackPrx);
            logger.info("Voto enviado al Centro exitosamente. ID: {}", voto.votoId);
        } catch (VotoDuplicadoException e) {
            // Si el voto ya fue procesado, marcarlo como confirmado
            logger.warn("Voto duplicado detectado: {}", e.mensaje);
            try {
                almacenamientoTransitorio.marcarVotoConfirmado(voto.votoId);
            } catch (ErrorPersistenciaException ex) {
                logger.error("Error al marcar voto duplicado como confirmado", ex);
            }
        } catch (ErrorPersistenciaException e) {
            logger.error("Error de persistencia en el Centro: {}", e.mensaje);
            // El voto queda como pendiente y será reintentado
        } catch (Exception e) {
            logger.error("Error al enviar voto al Centro", e);
            // El voto queda como pendiente y será reintentado
        }
    }
        
    /**
     * Verifica y reenvía los votos pendientes.
     */
    private void verificarYReenviarVotosPendientes() {
        logger.info("Verificando votos pendientes para reenvío");
        
        try {
            // Recuperar votos pendientes
            Voto[] votosPendientes = almacenamientoTransitorio.recuperarVotosPendientes();  

            if (votosPendientes.length == 0) {
                logger.info("No hay votos pendientes para reenviar");
                return;
            }
            
            logger.info("Encontrados {} votos pendientes para reenviar", votosPendientes.length);
            
            // Reenviar cada voto pendiente
            for (Voto voto : votosPendientes) {
                logger.info("Reintentando envío de voto. ID: {}", voto.votoId);
                enviarVotoAlCentro(voto);
            }
            
        } catch (Exception e) {
            logger.error("Error al verificar y reenviar votos pendientes", e);
        }
    }

    /**
     * Callback invocado por el Centro cuando se confirma la recepción de un voto.
     * 
     * @param votoId ID del voto confirmado
     * @param estado Estado del voto en el Centro
     * @param current Contexto de la llamada Ice
     */
    @Override
    public void confirmarRecepcionVoto(String votoId, EstadoVoto estado, Current current) {
        logger.info("Recibida confirmación del Centro. Voto ID: {}, Estado: {}", votoId, estado);
        
        try {
            // Si el voto fue procesado correctamente, actualizarlo en el almacenamiento
            if (estado == EstadoVoto.PROCESADO) {
                logger.info("Actualizando estado del voto a PROCESADO. ID: {}", votoId);
                almacenamientoTransitorio.marcarVotoConfirmado(votoId);
                logger.info("Voto confirmado exitosamente. ID: {}", votoId);
            } else if (estado == EstadoVoto.ERROR) {
                logger.warn("Centro reportó error al procesar voto. ID: {}", votoId);
                // En un sistema real, aquí podríamos implementar lógica adicional para manejar errores
            }
        } catch (Exception e) {
            logger.error("Error al procesar confirmación de voto", e);
        }
    }
}