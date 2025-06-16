package com.registraduria.votacion.central.database;

import Votacion.ConnectionManager;
import Votacion.ErrorPersistenciaException;
import Votacion.EstadoVoto;

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
 * ConnectionManager - Simulación de proxy de base de datos.
 * 
 * Simula la interfaz de conexión remota con base de datos para operaciones
 * CRUD de votos y candidatos. En una implementación real se conectaría
 * a una base de datos PostgreSQL/MySQL.
 */
public class ConnectionManagerImpl implements ConnectionManager {
    private static final Logger logger = LoggerFactory.getLogger(ConnectionManagerImpl.class);
    
    // Simulación de base de datos en memoria
    private final ConcurrentHashMap<String, VotoRecord> databaseVotos;
    private final ConcurrentHashMap<String, EstadoVoto> estadosVotos;
    
    // Archivos de persistencia
    private final String archivoDatabaseVotos;
    private final String archivoDatabaseLogs;
    
    // Locks para operaciones transaccionales
    private final ReentrantReadWriteLock lockDatabase = new ReentrantReadWriteLock();
    
    // Métricas
    private volatile AtomicLong totalOperaciones = new AtomicLong(0);
    private volatile AtomicLong totalVotosGuardados = new AtomicLong(0);
    private volatile AtomicLong totalConsultas = new AtomicLong(0);
    
    /**
     * Constructor del ConnectionManager.
     */
    public ConnectionManagerImpl(String dataDir) {
        this.databaseVotos = new ConcurrentHashMap<>();
        this.estadosVotos = new ConcurrentHashMap<>();
        this.archivoDatabaseVotos = dataDir + "/DatabaseVotos.db";
        this.archivoDatabaseLogs = dataDir + "/DatabaseOperaciones.log";
        
        logger.info("ConnectionManager inicializado");
        logger.info("Archivo database votos: {}", archivoDatabaseVotos);
        logger.info("Archivo database logs: {}", archivoDatabaseLogs);
        
        // Inicializar archivos
        inicializarArchivos();
        
        // Cargar datos existentes
        cargarDatosExistentes();
    }
    
    /**
     * Guarda un voto en la base de datos.
     * 
     * @param votoId ID único del voto
     * @param candidatoId ID del candidato
     * @param timestamp Timestamp del voto
     * @param hash Hash de verificación
     * @param current Contexto de Ice
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public void guardarVoto(String votoId, String candidatoId, String timestamp, String hash, Current current) 
            throws ErrorPersistenciaException {
        
        logger.info("=== GUARDAR VOTO EN DATABASE ===");
        logger.info("VotoId: {}, CandidatoId: {}, Timestamp: {}, Hash: {}", votoId, candidatoId, timestamp, hash);
        
        lockDatabase.writeLock().lock();
        try {
            totalOperaciones.incrementAndGet();
            
            // Validar parámetros
            validarParametrosVoto(votoId, candidatoId, timestamp, hash);
            
            // Verificar si el voto ya existe
            if (databaseVotos.containsKey(votoId)) {
                logger.warn("Voto {} ya existe en base de datos, actualizando...", votoId);
            }
            
            // Crear record del voto
            VotoRecord votoRecord = new VotoRecord(votoId, candidatoId, timestamp, hash, Instant.now().toString());
            
            // Guardar en "base de datos" (memoria)
            databaseVotos.put(votoId, votoRecord);
            estadosVotos.put(votoId, EstadoVoto.PROCESADO);
            
            // Persistir en archivo
            persistirVotoEnArchivo(votoRecord);
            
            // Log de operación
            escribirLogOperacion("GUARDAR_VOTO", votoId, 
                String.format("Candidato: %s, Hash: %s", candidatoId, hash));
            
            totalVotosGuardados.incrementAndGet();
            
            logger.info("Voto {} guardado exitosamente en base de datos", votoId);
            
        } catch (Exception e) {
            logger.error("Error guardando voto en database {}: {}", votoId, e.getMessage());
            throw new ErrorPersistenciaException("Error guardando en base de datos: " + e.getMessage());
        } finally {
            lockDatabase.writeLock().unlock();
        }
    }
    
    /**
     * Verifica el estado de un voto en la base de datos.
     * 
     * @param votoId ID del voto a verificar
     * @param current Contexto de Ice
     * @return Estado del voto
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public EstadoVoto verificarEstadoVoto(String votoId, Current current) throws ErrorPersistenciaException {
        
        logger.debug("=== VERIFICAR ESTADO VOTO ===");
        logger.debug("VotoId: {}", votoId);
        
        lockDatabase.readLock().lock();
        try {
            totalOperaciones.incrementAndGet();
            totalConsultas.incrementAndGet();
            
            if (votoId == null || votoId.trim().isEmpty()) {
                throw new ErrorPersistenciaException("VotoId no puede ser nulo o vacío");
            }
            
            // Buscar en base de datos
            EstadoVoto estado = estadosVotos.get(votoId);
            
            if (estado == null) {
                // Si no existe, considerar como no encontrado
                estado = EstadoVoto.ERROR;
                logger.debug("Voto {} no encontrado en base de datos", votoId);
            } else {
                logger.debug("Estado de voto {} en database: {}", votoId, estado);
            }
            
            // Log de consulta
            escribirLogOperacion("VERIFICAR_ESTADO", votoId, "Estado: " + estado);
            
            return estado;
            
        } catch (Exception e) {
            logger.error("Error verificando estado de voto {}: {}", votoId, e.getMessage());
            throw new ErrorPersistenciaException("Error consultando base de datos: " + e.getMessage());
        } finally {
            lockDatabase.readLock().unlock();
        }
    }
    
    /**
     * Guarda candidatos en la base de datos.
     * 
     * @param current Contexto de Ice
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public void guardarCandidatos(Current current) throws ErrorPersistenciaException {
        
        logger.info("=== GUARDAR CANDIDATOS EN DATABASE ===");
        
        lockDatabase.writeLock().lock();
        try {
            totalOperaciones.incrementAndGet();
            
            // Simular operación de guardado de candidatos
            // En una implementación real, esto ejecutaría SQL INSERT/UPDATE
            
            // Log de operación
            escribirLogOperacion("GUARDAR_CANDIDATOS", "BATCH", 
                "Operación de guardado masivo de candidatos");
            
            logger.info("Candidatos guardados exitosamente en base de datos");
            
        } catch (Exception e) {
            logger.error("Error guardando candidatos en database: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error guardando candidatos: " + e.getMessage());
        } finally {
            lockDatabase.writeLock().unlock();
        }
    }
    
    // === MÉTODOS PÚBLICOS ADICIONALES ===
    
    /**
     * Obtiene estadísticas de la base de datos.
     */
    public String obtenerEstadisticas() {
        return String.format(
            "ConnectionManager - Operaciones: %d, Votos: %d, Consultas: %d, Cache: %d",
            totalOperaciones.get(), totalVotosGuardados.get(), 
            totalConsultas.get(), databaseVotos.size()
        );
    }
    
    /**
     * Obtiene información de un voto específico.
     */
    public VotoRecord obtenerVoto(String votoId) {
        lockDatabase.readLock().lock();
        try {
            return databaseVotos.get(votoId);
        } finally {
            lockDatabase.readLock().unlock();
        }
    }
    
    /**
     * Obtiene el total de votos almacenados.
     */
    public int getTotalVotos() {
        return databaseVotos.size();
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
            Path votosPath = Paths.get(archivoDatabaseVotos);
            Path logsPath = Paths.get(archivoDatabaseLogs);
            
            // Crear directorios si no existen
            Files.createDirectories(votosPath.getParent());
            Files.createDirectories(logsPath.getParent());
            
            // Crear archivos si no existen
            if (!Files.exists(votosPath)) {
                Files.createFile(votosPath);
                logger.info("Archivo database votos creado: {}", archivoDatabaseVotos);
            }
            
            if (!Files.exists(logsPath)) {
                Files.createFile(logsPath);
                logger.info("Archivo database logs creado: {}", archivoDatabaseLogs);
            }
            
        } catch (Exception e) {
            logger.error("Error inicializando archivos: {}", e.getMessage());
            throw new RuntimeException("Error inicializando ConnectionManager", e);
        }
    }
    
    /**
     * Carga datos existentes desde archivos.
     */
    private void cargarDatosExistentes() {
        try {
            Path votosPath = Paths.get(archivoDatabaseVotos);
            
            if (Files.exists(votosPath) && Files.size(votosPath) > 0) {
                Files.lines(votosPath).forEach(linea -> {
                    try {
                        if (!linea.trim().isEmpty()) {
                            String[] partes = linea.split("\\|", 5);
                            if (partes.length >= 5) {
                                String votoId = partes[0];
                                String candidatoId = partes[1];
                                String timestamp = partes[2];
                                String hash = partes[3];
                                String fechaGuardado = partes[4];
                                
                                VotoRecord votoRecord = new VotoRecord(votoId, candidatoId, timestamp, hash, fechaGuardado);
                                databaseVotos.put(votoId, votoRecord);
                                estadosVotos.put(votoId, EstadoVoto.PROCESADO);
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Error cargando línea de voto: {}", linea);
                    }
                });
                
                logger.info("Cargados {} votos desde database", databaseVotos.size());
            }
            
        } catch (Exception e) {
            logger.error("Error cargando datos existentes: {}", e.getMessage());
        }
    }
    
    /**
     * Persiste un voto en archivo.
     */
    private void persistirVotoEnArchivo(VotoRecord voto) throws IOException {
        String linea = String.format("%s|%s|%s|%s|%s%n", 
            voto.votoId, voto.candidatoId, voto.timestamp, voto.hash, voto.fechaGuardado);
        
        Files.write(Paths.get(archivoDatabaseVotos), linea.getBytes(), 
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    
    /**
     * Escribe un log de operación.
     */
    private void escribirLogOperacion(String operacion, String votoId, String detalles) {
        try {
            String logEntry = String.format("DB_OP|%s|%s|%s|%s%n", 
                Instant.now().toString(), operacion, votoId, detalles);
            
            Files.write(Paths.get(archivoDatabaseLogs), logEntry.getBytes(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            
        } catch (Exception e) {
            logger.error("Error escribiendo log de operación: {}", e.getMessage());
        }
    }
    
    /**
     * Finaliza el ConnectionManager.
     */
    public void shutdown() {
        logger.info("Finalizando ConnectionManager...");
        
        // Escribir estadísticas finales
        escribirLogOperacion("SHUTDOWN", "SISTEMA", obtenerEstadisticas());
        
        logger.info("ConnectionManager finalizado");
        logger.info("Estadísticas finales: {}", obtenerEstadisticas());
    }
    
    // === CLASE INTERNA ===
    
    /**
     * Clase para representar un registro de voto en la base de datos.
     */
    public static class VotoRecord {
        public final String votoId;
        public final String candidatoId;
        public final String timestamp;
        public final String hash;
        public final String fechaGuardado;
        
        public VotoRecord(String votoId, String candidatoId, String timestamp, String hash, String fechaGuardado) {
            this.votoId = votoId;
            this.candidatoId = candidatoId;
            this.timestamp = timestamp;
            this.hash = hash;
            this.fechaGuardado = fechaGuardado;
        }
        
        @Override
        public String toString() {
            return String.format("VotoRecord{id='%s', candidato='%s', timestamp='%s', hash='%s', guardado='%s'}", 
                votoId, candidatoId, timestamp, hash, fechaGuardado);
        }
    }
} 