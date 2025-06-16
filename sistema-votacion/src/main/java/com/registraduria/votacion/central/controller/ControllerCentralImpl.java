package com.registraduria.votacion.central.controller;

import Votacion.ControllerCentral;
import Votacion.ErrorPersistenciaException;
import Votacion.EstadoVoto;
import Votacion.RegistroVotosPrx;
import Votacion.VotoDuplicadoException;

import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ControllerCentral - Punto crítico de procesamiento y consolidación de votos.
 * 
 * Recibe votos desde VotosBroker, realiza validación y anonimización,
 * luego los envía a RegistroVotos para persistencia.
 */
public class ControllerCentralImpl implements ControllerCentral {
    private static final Logger logger = LoggerFactory.getLogger(ControllerCentralImpl.class);
    
    // Dependencias
    private final RegistroVotosPrx registroVotos;
    
    // Cache de votos procesados para evitar duplicados
    private final ConcurrentHashMap<String, String> votosRecibidos;
    private final ConcurrentHashMap<String, String> hashesVotos;
    
    // Executor para procesamiento asíncrono
    private final ExecutorService processingExecutor;
    
    // Métricas del sistema
    private volatile AtomicLong totalVotosRecibidos = new AtomicLong(0);
    private volatile AtomicLong totalVotosProcesados = new AtomicLong(0);
    private volatile AtomicLong totalVotosDuplicados = new AtomicLong(0);
    private volatile AtomicLong totalErrores = new AtomicLong(0);
    
    /**
     * Constructor del ControllerCentral.
     */
    public ControllerCentralImpl(RegistroVotosPrx registroVotos) {
        this.registroVotos = registroVotos;
        this.votosRecibidos = new ConcurrentHashMap<>();
        this.hashesVotos = new ConcurrentHashMap<>();
        this.processingExecutor = Executors.newFixedThreadPool(10);
        
        logger.info("ControllerCentral inicializado");
        logger.info("Registro de votos configurado: {}", registroVotos != null ? "OK" : "ERROR");
    }
    
    /**
     * Recibe un voto desde una estación vía VotosBroker.
     * 
     * @param votoId ID único del voto
     * @param candidatoId ID del candidato elegido
     * @param estacionId ID de la estación de origen
     * @param hash Hash de verificación del voto
     * @param current Contexto de Ice
     * @throws ErrorPersistenciaException Si hay error de persistencia
     * @throws VotoDuplicadoException Si el voto ya fue procesado
     */
    @Override
    public void recibirVotoDesdeEstacion(String votoId, String candidatoId, String estacionId, String hash, Current current) 
            throws ErrorPersistenciaException, VotoDuplicadoException {
        
        logger.info("=== RECIBIR VOTO DESDE ESTACION ===");
        logger.info("VotoId: {}, CandidatoId: {}, EstacionId: {}, Hash: {}", votoId, candidatoId, estacionId, hash);
        
        long startTime = System.currentTimeMillis();
        totalVotosRecibidos.incrementAndGet();
        
        try {
            // Validación de parámetros
            validarParametrosVoto(votoId, candidatoId, estacionId, hash);
            
            // Verificar duplicados
            verificarVotoDuplicado(votoId, hash);
            
            // Anonimizar y procesar voto asíncronamente
            processingExecutor.submit(() -> {
                try {
                    procesarVotoAsincrono(votoId, candidatoId, estacionId, hash);
                } catch (Exception e) {
                    logger.error("Error en procesamiento asíncrono de voto {}: {}", votoId, e.getMessage());
                    totalErrores.incrementAndGet();
                }
            });
            
            // Registrar voto como recibido inmediatamente
            votosRecibidos.put(votoId, Instant.now().toString());
            hashesVotos.put(hash, votoId);
            
            long duration = System.currentTimeMillis() - startTime;
            logger.info("Voto {} recibido exitosamente en {}ms", votoId, duration);
            
        } catch (VotoDuplicadoException e) {
            totalVotosDuplicados.incrementAndGet();
            logger.warn("Voto duplicado detectado: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            totalErrores.incrementAndGet();
            logger.error("Error procesando voto {}: {}", votoId, e.getMessage());
            throw new ErrorPersistenciaException("Error en procesamiento: " + e.getMessage());
        }
    }
    
    /**
     * Confirma la recepción central de un voto.
     * 
     * @param votoId ID del voto a confirmar
     * @param estado Estado de confirmación
     * @param current Contexto de Ice
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public void confirmarRecepcionCentral(String votoId, EstadoVoto estado, Current current) 
            throws ErrorPersistenciaException {
        
        logger.info("=== CONFIRMAR RECEPCION CENTRAL ===");
        logger.info("VotoId: {}, Estado: {}", votoId, estado);
        
        try {
            // Validar que el voto fue recibido
            if (!votosRecibidos.containsKey(votoId)) {
                throw new ErrorPersistenciaException("Voto no encontrado en registros de recepción: " + votoId);
            }
            
            // Confirmar persistencia con RegistroVotos
            registroVotos.confirmarPersistenciaVoto(votoId, estado);
            
            logger.info("Recepción central confirmada para voto {}: {}", votoId, estado);
            
        } catch (Exception e) {
            totalErrores.incrementAndGet();
            logger.error("Error confirmando recepción central de voto {}: {}", votoId, e.getMessage());
            throw new ErrorPersistenciaException("Error en confirmación: " + e.getMessage());
        }
    }
    
    /**
     * Registra un voto anonimizado (usado internamente).
     * 
     * @param votoId ID del voto
     * @param candidatoId ID del candidato
     * @param timestamp Timestamp del voto
     * @param hash Hash anonimizado
     * @param current Contexto de Ice
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public void registrarVotoAnonimo(String votoId, String candidatoId, String timestamp, String hash, Current current) 
            throws ErrorPersistenciaException {
        
        logger.debug("Registrando voto anónimo: {}", votoId);
        
        try {
            // Delegar al RegistroVotos
            registroVotos.registrarVotoAnonimo(votoId, candidatoId, timestamp, hash);
            
            logger.debug("Voto anónimo {} registrado exitosamente", votoId);
            
        } catch (Exception e) {
            logger.error("Error registrando voto anónimo {}: {}", votoId, e.getMessage());
            throw new ErrorPersistenciaException("Error en registro anónimo: " + e.getMessage());
        }
    }
    
    /**
     * Confirma la persistencia de un voto (usado internamente).
     * 
     * @param votoId ID del voto
     * @param estado Estado de persistencia
     * @param current Contexto de Ice
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public void confirmarPersistenciaVoto(String votoId, EstadoVoto estado, Current current) 
            throws ErrorPersistenciaException {
        
        logger.debug("Confirmando persistencia de voto: {}", votoId);
        
        try {
            // Delegar al RegistroVotos
            registroVotos.confirmarPersistenciaVoto(votoId, estado);
            
            totalVotosProcesados.incrementAndGet();
            logger.debug("Persistencia confirmada para voto {}: {}", votoId, estado);
            
        } catch (Exception e) {
            logger.error("Error confirmando persistencia de voto {}: {}", votoId, e.getMessage());
            throw new ErrorPersistenciaException("Error en confirmación de persistencia: " + e.getMessage());
        }
    }
    
    // === MÉTODOS PRIVADOS ===
    
    /**
     * Valida los parámetros del voto.
     */
    private void validarParametrosVoto(String votoId, String candidatoId, String estacionId, String hash) 
            throws ErrorPersistenciaException {
        
        if (votoId == null || votoId.trim().isEmpty()) {
            throw new ErrorPersistenciaException("VotoId no puede ser nulo o vacío");
        }
        if (candidatoId == null || candidatoId.trim().isEmpty()) {
            throw new ErrorPersistenciaException("CandidatoId no puede ser nulo o vacío");
        }
        if (estacionId == null || estacionId.trim().isEmpty()) {
            throw new ErrorPersistenciaException("EstacionId no puede ser nulo o vacío");
        }
        if (hash == null || hash.trim().isEmpty()) {
            throw new ErrorPersistenciaException("Hash no puede ser nulo o vacío");
        }
    }
    
    /**
     * Verifica si el voto es duplicado.
     */
    private void verificarVotoDuplicado(String votoId, String hash) throws VotoDuplicadoException {
        
        // Verificar por votoId
        if (votosRecibidos.containsKey(votoId)) {
            String timestamp = votosRecibidos.get(votoId);
            throw new VotoDuplicadoException(votoId, 
                String.format("Voto duplicado por ID. Recibido anteriormente: %s", timestamp));
        }
        
        // Verificar por hash
        if (hashesVotos.containsKey(hash)) {
            String votoIdPrevio = hashesVotos.get(hash);
            throw new VotoDuplicadoException(votoId, 
                String.format("Voto duplicado por hash. Hash asociado a voto: %s", votoIdPrevio));
        }
    }
    
    /**
     * Procesa un voto de forma asíncrona.
     */
    private void procesarVotoAsincrono(String votoId, String candidatoId, String estacionId, String hash) {
        try {
            logger.debug("Procesando voto asíncrono: {}", votoId);
            
            // Generar timestamp del procesamiento
            String timestamp = Instant.now().toString();
            
            // Anonimizar el voto (remover información de estación)
            String hashAnonimizado = anonimizarVoto(votoId, candidatoId, timestamp);
            
            // Registrar voto anonimizado
            registroVotos.registrarVotoAnonimo(votoId, candidatoId, timestamp, hashAnonimizado);
            
            // Confirmar persistencia
            confirmarPersistenciaVoto(votoId, EstadoVoto.PROCESADO, null);
            
            logger.info("Voto {} procesado exitosamente de forma asíncrona", votoId);
            
        } catch (Exception e) {
            logger.error("Error en procesamiento asíncrono de voto {}: {}", votoId, e.getMessage());
        }
    }
    
    /**
     * Anonimiza un voto eliminando información de trazabilidad.
     */
    private String anonimizarVoto(String votoId, String candidatoId, String timestamp) {
        try {
            // Crear hash anonimizado usando solo votoId + candidatoId + timestamp
            String dataToHash = votoId + candidatoId + timestamp;
            
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(dataToHash.getBytes());
            
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            
            return sb.toString();
            
        } catch (NoSuchAlgorithmException e) {
            logger.error("Error generando hash anonimizado: {}", e.getMessage());
            return "HASH_ERROR_" + System.currentTimeMillis();
        }
    }
    
    /**
     * Obtiene métricas del sistema.
     */
    public String obtenerMetricas() {
        return String.format(
            "ControllerCentral - Recibidos: %d, Procesados: %d, Duplicados: %d, Errores: %d",
            totalVotosRecibidos.get(), totalVotosProcesados.get(), 
            totalVotosDuplicados.get(), totalErrores.get()
        );
    }
    
    /**
     * Finaliza el ControllerCentral y libera recursos.
     */
    public void shutdown() {
        logger.info("Finalizando ControllerCentral...");
        
        if (processingExecutor != null && !processingExecutor.isShutdown()) {
            processingExecutor.shutdown();
        }
        
        logger.info("ControllerCentral finalizado");
        logger.info("Métricas finales: {}", obtenerMetricas());
    }
} 