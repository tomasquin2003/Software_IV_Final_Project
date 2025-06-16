package com.registraduria.votacion.central.registro;

import Votacion.ConnectionManagerPrx;
import Votacion.ErrorPersistenciaException;
import Votacion.EstadoVoto;
import Votacion.RegistroVotos;

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
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * RegistroVotos - Encargado de la persistencia real de votos anonimizados.
 * 
 * Se comunica con DatabaseProxy (ConnectionManager) para operaciones CRUD
 * y mantiene cache local para rendimiento.
 */
public class RegistroVotosImpl implements RegistroVotos {
    private static final Logger logger = LoggerFactory.getLogger(RegistroVotosImpl.class);
    
    // Dependencias
    private final ConnectionManagerPrx connectionManager;
    
    // Cache de votos para acceso rápido
    private final ConcurrentHashMap<String, VotoAnonimizado> votosCache;
    private final ConcurrentHashMap<String, EstadoVoto> estadosVotos;
    
    // Archivos de respaldo local
    private final String archivoPersistencia;
    private final String archivoAuditoria;
    
    // Locks para operaciones transaccionales
    private final ReentrantReadWriteLock lockVotos = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock lockAuditoria = new ReentrantReadWriteLock();
    
    // Métricas
    private volatile AtomicLong totalVotosRegistrados = new AtomicLong(0);
    private volatile AtomicLong totalConfirmaciones = new AtomicLong(0);
    private volatile AtomicLong totalErroresPersistencia = new AtomicLong(0);
    
    /**
     * Constructor del RegistroVotos.
     */
    public RegistroVotosImpl(ConnectionManagerPrx connectionManager, String dataDir) {
        this.connectionManager = connectionManager;
        this.votosCache = new ConcurrentHashMap<>();
        this.estadosVotos = new ConcurrentHashMap<>();
        this.archivoPersistencia = dataDir + "/VotosAnonimizados.db";
        this.archivoAuditoria = dataDir + "/AuditoriaVotos.log";
        
        logger.info("RegistroVotos inicializado");
        logger.info("ConnectionManager configurado: {}", connectionManager != null ? "OK" : "ERROR");
        logger.info("Archivo persistencia: {}", archivoPersistencia);
        logger.info("Archivo auditoría: {}", archivoAuditoria);
        
        // Inicializar archivos
        inicializarArchivos();
        
        // Cargar votos existentes
        cargarVotosExistentes();
    }
    
    /**
     * Registra un voto anonimizado en el sistema.
     * 
     * @param votoId ID único del voto
     * @param candidatoId ID del candidato elegido
     * @param timestamp Timestamp del voto
     * @param hash Hash anonimizado del voto
     * @param current Contexto de Ice
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public void registrarVotoAnonimo(String votoId, String candidatoId, String timestamp, String hash, Current current) 
            throws ErrorPersistenciaException {
        
        logger.info("=== REGISTRAR VOTO ANONIMO ===");
        logger.info("VotoId: {}, CandidatoId: {}, Timestamp: {}, Hash: {}", votoId, candidatoId, timestamp, hash);
        
        lockVotos.writeLock().lock();
        try {
            // Validar parámetros
            validarParametrosVoto(votoId, candidatoId, timestamp, hash);
            
            // Verificar que el voto no existe
            if (votosCache.containsKey(votoId)) {
                throw new ErrorPersistenciaException("Voto ya existe en registro: " + votoId);
            }
            
            // Crear voto anonimizado
            VotoAnonimizado voto = new VotoAnonimizado(votoId, candidatoId, timestamp, hash);
            
            // Persistir en base de datos remota
            try {
                connectionManager.guardarVoto(votoId, candidatoId, timestamp, hash);
                logger.debug("Voto {} guardado en base de datos remota", votoId);
            } catch (Exception e) {
                logger.warn("Error guardando en BD remota, persistiendo localmente: {}", e.getMessage());
                // Continuar con persistencia local como fallback
            }
            
            // Guardar en cache y archivo local
            votosCache.put(votoId, voto);
            estadosVotos.put(votoId, EstadoVoto.RECIBIDO);
            
            // Persistir localmente
            persistirVotoLocal(voto);
            
            // Escribir log de auditoría
            escribirAuditoria("REGISTRO_VOTO", votoId, 
                String.format("Candidato: %s, Hash: %s", candidatoId, hash));
            
            totalVotosRegistrados.incrementAndGet();
            
            logger.info("Voto {} registrado exitosamente como anónimo", votoId);
            
        } catch (Exception e) {
            totalErroresPersistencia.incrementAndGet();
            logger.error("Error registrando voto anónimo {}: {}", votoId, e.getMessage());
            throw new ErrorPersistenciaException("Error en registro: " + e.getMessage());
        } finally {
            lockVotos.writeLock().unlock();
        }
    }
    
    /**
     * Confirma la persistencia de un voto en el sistema.
     * 
     * @param votoId ID del voto a confirmar
     * @param estado Estado de persistencia
     * @param current Contexto de Ice
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public void confirmarPersistenciaVoto(String votoId, EstadoVoto estado, Current current) 
            throws ErrorPersistenciaException {
        
        logger.info("=== CONFIRMAR PERSISTENCIA VOTO ===");
        logger.info("VotoId: {}, Estado: {}", votoId, estado);
        
        lockVotos.writeLock().lock();
        try {
            // Verificar que el voto existe
            if (!votosCache.containsKey(votoId)) {
                throw new ErrorPersistenciaException("Voto no encontrado para confirmación: " + votoId);
            }
            
            // Actualizar estado
            estadosVotos.put(votoId, estado);
            
            // Verificar estado en base de datos remota si es posible
            try {
                EstadoVoto estadoRemoto = connectionManager.verificarEstadoVoto(votoId);
                if (estadoRemoto != estado) {
                    logger.warn("Estado local {} difiere del remoto {} para voto {}", 
                        estado, estadoRemoto, votoId);
                }
            } catch (Exception e) {
                logger.debug("No se pudo verificar estado remoto: {}", e.getMessage());
            }
            
            // Escribir confirmación de auditoría
            escribirAuditoria("CONFIRMACION_PERSISTENCIA", votoId, 
                String.format("Estado: %s", estado));
            
            totalConfirmaciones.incrementAndGet();
            
            logger.info("Persistencia confirmada para voto {}: {}", votoId, estado);
            
        } catch (Exception e) {
            totalErroresPersistencia.incrementAndGet();
            logger.error("Error confirmando persistencia de voto {}: {}", votoId, e.getMessage());
            throw new ErrorPersistenciaException("Error en confirmación: " + e.getMessage());
        } finally {
            lockVotos.writeLock().unlock();
        }
    }
    
    /**
     * Guarda un voto (delegación a ConnectionManager).
     * 
     * @param votoId ID del voto
     * @param candidatoId ID del candidato
     * @param timestamp Timestamp del voto
     * @param hash Hash del voto
     * @param current Contexto de Ice
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public void guardarVoto(String votoId, String candidatoId, String timestamp, String hash, Current current) 
            throws ErrorPersistenciaException {
        
        logger.debug("Guardando voto: {}", votoId);
        
        try {
            // Delegar al ConnectionManager
            connectionManager.guardarVoto(votoId, candidatoId, timestamp, hash);
            
            logger.debug("Voto {} guardado exitosamente vía ConnectionManager", votoId);
            
        } catch (Exception e) {
            logger.error("Error guardando voto {}: {}", votoId, e.getMessage());
            throw new ErrorPersistenciaException("Error guardando voto: " + e.getMessage());
        }
    }
    
    /**
     * Verifica el estado de un voto (delegación a ConnectionManager).
     * 
     * @param votoId ID del voto a verificar
     * @param current Contexto de Ice
     * @return Estado del voto
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public EstadoVoto verificarEstadoVoto(String votoId, Current current) throws ErrorPersistenciaException {
        
        logger.debug("Verificando estado de voto: {}", votoId);
        
        try {
            // Primero verificar en cache local
            EstadoVoto estadoLocal = estadosVotos.get(votoId);
            if (estadoLocal != null) {
                logger.debug("Estado local encontrado para voto {}: {}", votoId, estadoLocal);
                return estadoLocal;
            }
            
            // Si no está en cache, consultar ConnectionManager
            EstadoVoto estadoRemoto = connectionManager.verificarEstadoVoto(votoId);
            
            // Actualizar cache
            estadosVotos.put(votoId, estadoRemoto);
            
            logger.debug("Estado remoto para voto {}: {}", votoId, estadoRemoto);
            return estadoRemoto;
            
        } catch (Exception e) {
            logger.error("Error verificando estado de voto {}: {}", votoId, e.getMessage());
            throw new ErrorPersistenciaException("Error verificando estado: " + e.getMessage());
        }
    }
    
    // === MÉTODOS PRIVADOS ===
    
    /**
     * Valida los parámetros del voto.
     */
    private void validarParametrosVoto(String votoId, String candidatoId, String timestamp, String hash) 
            throws ErrorPersistenciaException {
        
        if (votoId == null || votoId.trim().isEmpty()) {
            throw new ErrorPersistenciaException("VotoId no puede ser nulo o vacío");
        }
        if (candidatoId == null || candidatoId.trim().isEmpty()) {
            throw new ErrorPersistenciaException("CandidatoId no puede ser nulo o vacío");
        }
        if (timestamp == null || timestamp.trim().isEmpty()) {
            throw new ErrorPersistenciaException("Timestamp no puede ser nulo o vacío");
        }
        if (hash == null || hash.trim().isEmpty()) {
            throw new ErrorPersistenciaException("Hash no puede ser nulo o vacío");
        }
    }
    
    /**
     * Inicializa archivos de persistencia.
     */
    private void inicializarArchivos() {
        try {
            Path persistenciaPath = Paths.get(archivoPersistencia);
            Path auditoriaPath = Paths.get(archivoAuditoria);
            
            // Crear directorios si no existen
            Files.createDirectories(persistenciaPath.getParent());
            Files.createDirectories(auditoriaPath.getParent());
            
            // Crear archivos si no existen
            if (!Files.exists(persistenciaPath)) {
                Files.createFile(persistenciaPath);
                logger.info("Archivo de persistencia creado: {}", archivoPersistencia);
            }
            
            if (!Files.exists(auditoriaPath)) {
                Files.createFile(auditoriaPath);
                logger.info("Archivo de auditoría creado: {}", archivoAuditoria);
            }
            
        } catch (Exception e) {
            logger.error("Error inicializando archivos: {}", e.getMessage());
            throw new RuntimeException("Error inicializando RegistroVotos", e);
        }
    }
    
    /**
     * Carga votos existentes desde archivo local.
     */
    private void cargarVotosExistentes() {
        try {
            Path persistenciaPath = Paths.get(archivoPersistencia);
            
            if (Files.exists(persistenciaPath) && Files.size(persistenciaPath) > 0) {
                Files.lines(persistenciaPath).forEach(linea -> {
                    try {
                        if (!linea.trim().isEmpty()) {
                            String[] partes = linea.split("\\|", 4);
                            if (partes.length >= 4) {
                                String votoId = partes[0];
                                String candidatoId = partes[1];
                                String timestamp = partes[2];
                                String hash = partes[3];
                                
                                VotoAnonimizado voto = new VotoAnonimizado(votoId, candidatoId, timestamp, hash);
                                votosCache.put(votoId, voto);
                                estadosVotos.put(votoId, EstadoVoto.RECIBIDO);
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Error cargando línea de voto: {}", linea);
                    }
                });
                
                logger.info("Cargados {} votos desde archivo local", votosCache.size());
            }
            
        } catch (Exception e) {
            logger.error("Error cargando votos existentes: {}", e.getMessage());
        }
    }
    
    /**
     * Persiste un voto en archivo local.
     */
    private void persistirVotoLocal(VotoAnonimizado voto) throws IOException {
        String linea = String.format("%s|%s|%s|%s%n", 
            voto.votoId, voto.candidatoId, voto.timestamp, voto.hash);
        
        Files.write(Paths.get(archivoPersistencia), linea.getBytes(), 
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    
    /**
     * Escribe un log de auditoría.
     */
    private void escribirAuditoria(String operacion, String votoId, String detalles) {
        lockAuditoria.writeLock().lock();
        try {
            String logEntry = String.format("AUDIT|%s|%s|%s|%s%n", 
                Instant.now().toString(), operacion, votoId, detalles);
            
            Files.write(Paths.get(archivoAuditoria), logEntry.getBytes(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            
        } catch (Exception e) {
            logger.error("Error escribiendo auditoría: {}", e.getMessage());
        } finally {
            lockAuditoria.writeLock().unlock();
        }
    }
    
    /**
     * Obtiene métricas del sistema.
     */
    public String obtenerMetricas() {
        return String.format(
            "RegistroVotos - Registrados: %d, Confirmaciones: %d, Errores: %d, Cache: %d",
            totalVotosRegistrados.get(), totalConfirmaciones.get(), 
            totalErroresPersistencia.get(), votosCache.size()
        );
    }
    
    /**
     * Finaliza el RegistroVotos.
     */
    public void shutdown() {
        logger.info("Finalizando RegistroVotos...");
        
        // Escribir métricas finales
        escribirAuditoria("SHUTDOWN", "SISTEMA", obtenerMetricas());
        
        logger.info("RegistroVotos finalizado");
        logger.info("Métricas finales: {}", obtenerMetricas());
    }
    
    // === CLASE INTERNA ===
    
    /**
     * Clase para representar un voto anonimizado.
     */
    private static class VotoAnonimizado {
        final String votoId;
        final String candidatoId;
        final String timestamp;
        final String hash;
        
        VotoAnonimizado(String votoId, String candidatoId, String timestamp, String hash) {
            this.votoId = votoId;
            this.candidatoId = candidatoId;
            this.timestamp = timestamp;
            this.hash = hash;
        }
    }
} 