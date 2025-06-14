package com.registraduria.votacion.estacion.votacion;

import Votacion.AlmacenamientoTransitorioPrx;
import Votacion.ErrorPersistenciaException;
import Votacion.EstadoVoto;
import Votacion.GestorEnvioVotosCallback;
import Votacion.GestorEnvioVotosCallbackPrx;
import Votacion.GestorRecepcionVotosPrx;
import Votacion.Voto;
import Votacion.VotoDuplicadoException;

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
public class GestorEnvioVotosImpl implements Votacion.GestorEnvioVotosCallback {
    private static final Logger logger = LoggerFactory.getLogger(GestorEnvioVotosImpl.class);
      // Proxy para acceder al servicio de almacenamiento transitorio
    private final AlmacenamientoTransitorioPrx almacenamientoTransitorio;
    
    // Implementación directa para acceder a métodos extendidos
    private final com.registraduria.votacion.estacion.persistencia.AlmacenamientoTransitorioImpl almacenamientoImpl;
      // Proxy para acceder al servicio de recepción de votos en el Centro
    private final GestorRecepcionVotosPrx gestorRecepcionVotos;
    
    // ID de la estación de votación
    private final String estacionId;
    
    // Adaptador Ice para crear proxies
    private final com.zeroc.Ice.ObjectAdapter adapter;

    // Servicio para programar reintentos periódicos
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    // Intervalos para reintentos (en segundos)
    private static final int INTERVALO_REINTENTO_INICIAL = 5;
    private static final int INTERVALO_VERIFICACION_PENDIENTES = 30;
    private static final int MAX_REINTENTOS = 10;    /**
     * Constructor que inicializa el gestor con las dependencias necesarias.
     * 
     * @param almacenamientoTransitorio Proxy para el servicio de almacenamiento transitorio
     * @param almacenamientoImpl Implementación directa del almacenamiento transitorio
     * @param gestorRecepcionVotos Proxy para el servicio de recepción de votos
     * @param estacionId ID de la estación de votación
     * @param adapter Adaptador Ice para crear proxies
     */
    public GestorEnvioVotosImpl(
            AlmacenamientoTransitorioPrx almacenamientoTransitorio,
            com.registraduria.votacion.estacion.persistencia.AlmacenamientoTransitorioImpl almacenamientoImpl,
            GestorRecepcionVotosPrx gestorRecepcionVotos,
            String estacionId,
            com.zeroc.Ice.ObjectAdapter adapter) {
        
        this.almacenamientoTransitorio = almacenamientoTransitorio;
        this.almacenamientoImpl = almacenamientoImpl;
        this.gestorRecepcionVotos = gestorRecepcionVotos;
        this.estacionId = estacionId;
        this.adapter = adapter;
        
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
     * @param cedulaVotante Cédula del votante que emite el voto
     * @return ID único asignado al voto
     * @throws Exception si hay un error en el proceso
     */
    public String enviarVoto(String candidatoId, String cedulaVotante) throws Exception {
        // Generar ID único para el voto
        String votoId = UUID.randomUUID().toString();
        
        logger.info("Procesando nuevo voto. ID: {}, Candidato: {}, Cédula: {}", votoId, candidatoId, cedulaVotante);        try {
            // CORRECCIÓN: Usar la implementación directa para almacenar con cédula
            almacenamientoImpl.almacenarVotoTransitorio(votoId, candidatoId, cedulaVotante, EstadoVoto.PENDIENTE, null);
            
            // Registrar la relación votoId -> cédula en el contexto
            com.registraduria.votacion.estacion.util.EstacionContext.getInstance()
                .registrarVotoCedula(votoId, cedulaVotante);
            
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
     * Método legacy para mantener compatibilidad con código existente.
     * 
     * @param candidatoId ID del candidato elegido
     * @return ID único asignado al voto
     * @throws Exception si hay un error en el proceso
     */    public String enviarVoto(String candidatoId) throws Exception {
        return enviarVoto(candidatoId, "DESCONOCIDA");
    }
      /**
     * Envía un voto al Centro de Votación.
     * 
     * @param voto Objeto Voto a enviar
     */
    private void enviarVotoAlCentro(Voto voto) {
        logger.info("Enviando voto al Centro. ID: {}", voto.votoId);
        
        try {
            // Obtener la cédula del contexto
            String cedulaVotante = com.registraduria.votacion.estacion.util.EstacionContext.getInstance()
                .obtenerCedulaPorVotoId(voto.votoId);
            
            // Usar el proxy del callback que ya está registrado en el adaptador
            // El ID debe coincidir con el usado en EstacionVotacionApp
            com.zeroc.Ice.Identity callbackId = new com.zeroc.Ice.Identity("GestorEnvioVotos", "");
            Votacion.GestorEnvioVotosCallbackPrx callbackPrx = 
                Votacion.GestorEnvioVotosCallbackPrx.uncheckedCast(adapter.createProxy(callbackId));
            
            // Crear contexto con metadatos para enviar la cédula
            java.util.Map<String, String> ctx = new java.util.HashMap<>();
            ctx.put("cedulaVotante", cedulaVotante);
            
            // Enviar el voto al Centro usando el proxy del callback con contexto
            gestorRecepcionVotos.recibirVoto(voto, callbackPrx, ctx); 
            logger.info("Voto enviado al Centro exitosamente. ID: {}, Cédula: {}", voto.votoId, cedulaVotante);
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
        
    // Modificar el método verificarYReenviarVotosPendientes
    private void verificarYReenviarVotosPendientes() {
        logger.info("Verificando votos pendientes para reenvío");
        
        try {
            // Verificar si hay votos pendientes
            boolean hayPendientes = almacenamientoTransitorio.hayVotosPendientes();
            
            if (!hayPendientes) {
                logger.info("No hay votos pendientes para reenviar");
                return;
            }
            
            // Obtener los IDs de los votos pendientes
            String idsStr = almacenamientoTransitorio.obtenerIdsVotosPendientes();
            String[] ids = idsStr.split(",");
            
            if (ids.length == 0 || (ids.length == 1 && ids[0].isEmpty())) {
                logger.info("No hay votos pendientes para reenviar");
                return;
            }
            
            logger.info("Encontrados {} votos pendientes para reenviar", ids.length);
            
            // Reenviar cada voto pendiente
            for (String votoId : ids) {
                if (votoId.isEmpty()) continue;
                
                logger.info("Reintentando envío de voto. ID: {}", votoId);
                Voto voto = almacenamientoTransitorio.obtenerVotoPendiente(votoId);
                enviarVotoAlCentro(voto);
            }
            
        } catch (Exception e) {
            logger.error("Error al verificar y reenviar votos pendientes", e);
        }
    }    /**
     * Callback invocado por el Centro cuando se confirma la recepción de un voto.
     * 
     * @param votoId ID del voto confirmado
     * @param estado Estado del voto en el Centro
     * @param current Contexto de la llamada Ice
     */
    @Override
    public void confirmarRecepcionVoto(String votoId, EstadoVoto estado, com.zeroc.Ice.Current current) {
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
    
    // Mapa temporal para relacionar votoId con cédula del votante
    private final java.util.concurrent.ConcurrentHashMap<String, String> votoIdToCedula = new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * Registra la relación entre un votoId y la cédula del votante.
     * Esto permite que el almacenamiento local pueda acceder a la cédula.
     * 
     * @param votoId ID del voto
     * @param cedula Cédula del votante
     */
    public void registrarVotoCedula(String votoId, String cedula) {
        votoIdToCedula.put(votoId, cedula);
        logger.debug("Registrada relación votoId {} -> cédula {}", votoId, cedula);
    }
    
    /**
     * Obtiene la cédula asociada a un votoId.
     * 
     * @param votoId ID del voto
     * @return Cédula del votante o "DESCONOCIDA" si no se encuentra
     */
    public String obtenerCedulaPorVotoId(String votoId) {
        return votoIdToCedula.getOrDefault(votoId, "DESCONOCIDA");
    }
}