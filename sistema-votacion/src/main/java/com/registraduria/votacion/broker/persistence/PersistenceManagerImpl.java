package com.registraduria.votacion.broker.persistence;

import Votacion.ErrorPersistenciaException;
import Votacion.PersistenceManager;
import Votacion.EstadoVoto;

import com.registraduria.votacion.broker.model.DatosBroker;

import com.zeroc.Ice.Current;
import com.zeroc.Ice.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * PersistenceManager - Maneja la persistencia de votos pendientes y logs del VotosBroker.
 * 
 * Implementa persistencia transaccional para evitar pérdida de votos y mantiene
 * logs de auditoría para todas las operaciones del broker.
 */
public class PersistenceManagerImpl implements PersistenceManager {
    private static final Logger logger = LoggerFactory.getLogger(PersistenceManagerImpl.class);
    
    // Archivos de persistencia
    private final String votosPendientesDbPath;
    private final String brokerLogsDbPath;
    
    // Cache en memoria para acceso rápido
    private final ConcurrentHashMap<String, DatosBroker> cacheVotosPendientes;
    private final ConcurrentHashMap<String, String> cacheVotosEnviados;
    
    // Locks para operaciones transaccionales
    private final ReentrantReadWriteLock lockVotosPendientes = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock lockLogs = new ReentrantReadWriteLock();
    
    // Métricas
    private volatile long totalVotosAlmacenados = 0;
    private volatile long totalVotosEnviados = 0;
    private volatile long totalLogsEscritos = 0;
    
    /**
     * Constructor del PersistenceManager.
     * 
     * @param votosPendientesDbPath Ruta del archivo de votos pendientes
     * @param brokerLogsDbPath Ruta del archivo de logs del broker
     */
    public PersistenceManagerImpl(String votosPendientesDbPath, String brokerLogsDbPath) {
        this.votosPendientesDbPath = votosPendientesDbPath;
        this.brokerLogsDbPath = brokerLogsDbPath;
        this.cacheVotosPendientes = new ConcurrentHashMap<>();
        this.cacheVotosEnviados = new ConcurrentHashMap<>();
        
        logger.info("PersistenceManager inicializado");
        logger.info("Archivo votos pendientes: {}", votosPendientesDbPath);
        logger.info("Archivo logs: {}", brokerLogsDbPath);
        
        // Inicializar archivos y cargar datos existentes
        inicializarArchivos();
        cargarDatosExistentes();
    }
    
    /**
     * Implementación de interfaz ICE que acepta Value.
     * Convierte Value a DatosBroker internamente.
     */
    @Override
    public void almacenarVotoPendiente(String votoId, Value datos, Current current) 
            throws ErrorPersistenciaException {
        
        logger.debug("Almacenando voto pendiente: {}", votoId);
        
        if (votoId == null || votoId.trim().isEmpty()) {
            throw new ErrorPersistenciaException("VotoId no puede ser nulo o vacío");
        }
        
        if (datos == null) {
            throw new ErrorPersistenciaException("Datos del voto no pueden ser nulos");
        }
        
        // Convertir Value a DatosBroker
        DatosBroker datosBroker = new DatosBroker(
            votoId,
            datos.toString(), // Convertir Value a String
            Instant.now().toString(),
            EstadoVoto.PENDIENTE
        );
        
        lockVotosPendientes.writeLock().lock();
        try {
            // Verificar si el voto ya existe
            if (cacheVotosPendientes.containsKey(votoId)) {
                logger.debug("Voto {} ya existe, actualizando datos", votoId);
            }
            
            // Almacenar en cache
            cacheVotosPendientes.put(votoId, datosBroker);
            
            // Persistir a archivo inmediatamente (transaccional)
            persistirVotoPendiente(votoId, datosBroker);
            
            totalVotosAlmacenados++;
            
            // Escribir log de auditoría
            escribirLogAuditoria("ALMACENAR_VOTO_PENDIENTE", votoId, 
                String.format("Datos: %s", datosBroker.datos));
            
            logger.info("Voto {} almacenado exitosamente como pendiente", votoId);
            
        } catch (Exception e) {
            logger.error("Error almacenando voto pendiente {}: {}", votoId, e.getMessage());
            throw new ErrorPersistenciaException("Error de persistencia: " + e.getMessage());
        } finally {
            lockVotosPendientes.writeLock().unlock();
        }
    }
    
    /**
     * Recupera todos los votos pendientes de envío.
     * 
     * @param current Contexto de Ice
     * @return String con IDs de votos pendientes separados por coma
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public String recuperarVotosPendientesEnvio(Current current) throws ErrorPersistenciaException {
        
        logger.debug("Recuperando votos pendientes de envío");
        
        lockVotosPendientes.readLock().lock();
        try {
            // Obtener IDs de votos pendientes desde cache
            String votosPendientes = cacheVotosPendientes.keySet()
                .stream()
                .filter(votoId -> !cacheVotosEnviados.containsKey(votoId))
                .collect(Collectors.joining(","));
            
            logger.debug("Votos pendientes encontrados: {}", votosPendientes.isEmpty() ? "ninguno" : votosPendientes);
            
            // Escribir log de consulta
            escribirLogAuditoria("RECUPERAR_VOTOS_PENDIENTES", "CONSULTA", 
                String.format("Encontrados: %d votos", cacheVotosPendientes.size()));
            
            return votosPendientes;
            
        } catch (Exception e) {
            logger.error("Error recuperando votos pendientes: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error recuperando votos pendientes: " + e.getMessage());
        } finally {
            lockVotosPendientes.readLock().unlock();
        }
    }
    
    /**
     * Marca un voto como enviado exitosamente.
     * 
     * @param votoId ID del voto enviado
     * @param current Contexto de Ice
     * @throws ErrorPersistenciaException Si hay error de persistencia
     */
    @Override
    public void marcarVotoEnviado(String votoId, Current current) throws ErrorPersistenciaException {
        
        logger.debug("Marcando voto como enviado: {}", votoId);
        
        if (votoId == null || votoId.trim().isEmpty()) {
            throw new ErrorPersistenciaException("VotoId no puede ser nulo o vacío");
        }
        
        lockVotosPendientes.writeLock().lock();
        try {
            // Verificar que el voto existe en pendientes
            if (!cacheVotosPendientes.containsKey(votoId)) {
                logger.warn("Intentando marcar como enviado voto inexistente: {}", votoId);
                throw new ErrorPersistenciaException("Voto no encontrado en pendientes: " + votoId);
            }
            
            // Mover de pendientes a enviados
            DatosBroker datosVoto = cacheVotosPendientes.remove(votoId);
            cacheVotosEnviados.put(votoId, Instant.now().toString());
            
            // Persistir cambios
            persistirVotoEnviado(votoId, datosVoto);
            removerVotoPendienteDeArchivo(votoId);
            
            totalVotosEnviados++;
            
            // Escribir log de auditoría
            escribirLogAuditoria("MARCAR_VOTO_ENVIADO", votoId, 
                String.format("Movido de pendientes a enviados"));
            
            logger.info("Voto {} marcado exitosamente como enviado", votoId);
            
        } catch (Exception e) {
            logger.error("Error marcando voto como enviado {}: {}", votoId, e.getMessage());
            throw new ErrorPersistenciaException("Error marcando voto como enviado: " + e.getMessage());
        } finally {
            lockVotosPendientes.writeLock().unlock();
        }
    }
    
    /**
     * Obtiene los datos de un voto específico.
     */
    public DatosBroker obtenerDatosVoto(String votoId) throws ErrorPersistenciaException {
        lockVotosPendientes.readLock().lock();
        try {
            DatosBroker datos = cacheVotosPendientes.get(votoId);
            if (datos == null) {
                throw new ErrorPersistenciaException("Voto no encontrado: " + votoId);
            }
            return datos;
        } finally {
            lockVotosPendientes.readLock().unlock();
        }
    }
    
    /**
     * Verifica si un voto está pendiente.
     */
    public boolean esVotoPendiente(String votoId) {
        lockVotosPendientes.readLock().lock();
        try {
            return cacheVotosPendientes.containsKey(votoId) && !cacheVotosEnviados.containsKey(votoId);
        } finally {
            lockVotosPendientes.readLock().unlock();
        }
    }
    
    /**
     * Obtiene estadísticas de persistencia.
     */
    public String getEstadisticasPersistencia() {
        return String.format(
            "Votos Pendientes: %d, Votos Enviados: %d, Total Almacenados: %d, Logs Escritos: %d",
            cacheVotosPendientes.size(), cacheVotosEnviados.size(), 
            totalVotosAlmacenados, totalLogsEscritos
        );
    }
    
    /**
     * Método auxiliar interno para almacenar DatosBroker directamente.
     * Usado por componentes internos del broker.
     */
    public void almacenarVotoPendienteInterno(String votoId, DatosBroker datos) 
            throws ErrorPersistenciaException {
        
        logger.debug("Almacenando voto pendiente interno: {}", votoId);
        
        if (votoId == null || votoId.trim().isEmpty()) {
            throw new ErrorPersistenciaException("VotoId no puede ser nulo o vacío");
        }
        
        if (datos == null) {
            throw new ErrorPersistenciaException("Datos del voto no pueden ser nulos");
        }
        
        lockVotosPendientes.writeLock().lock();
        try {
            // Verificar si el voto ya existe
            if (cacheVotosPendientes.containsKey(votoId)) {
                logger.debug("Voto {} ya existe, actualizando datos", votoId);
            }
            
            // Almacenar en cache
            cacheVotosPendientes.put(votoId, datos);
            
            // Persistir a archivo inmediatamente (transaccional)
            persistirVotoPendiente(votoId, datos);
            
            totalVotosAlmacenados++;
            
            // Escribir log de auditoría
            escribirLogAuditoria("ALMACENAR_VOTO_PENDIENTE_INTERNO", votoId, 
                String.format("Datos: %s", datos.datos));
            
            logger.info("Voto {} almacenado exitosamente como pendiente (interno)", votoId);
            
        } catch (Exception e) {
            logger.error("Error almacenando voto pendiente interno {}: {}", votoId, e.getMessage());
            throw new ErrorPersistenciaException("Error de persistencia: " + e.getMessage());
        } finally {
            lockVotosPendientes.writeLock().unlock();
        }
    }
    
    // === MÉTODOS PRIVADOS ===
    
    /**
     * Inicializa los archivos de persistencia.
     */
    private void inicializarArchivos() {
        try {
            // Crear directorios si no existen
            Path votosPendientesPath = Paths.get(votosPendientesDbPath);
            Path brokerLogsPath = Paths.get(brokerLogsDbPath);
            
            Files.createDirectories(votosPendientesPath.getParent());
            Files.createDirectories(brokerLogsPath.getParent());
            
            // Crear archivos si no existen
            if (!Files.exists(votosPendientesPath)) {
                Files.createFile(votosPendientesPath);
                logger.info("Archivo de votos pendientes creado: {}", votosPendientesDbPath);
            }
            
            if (!Files.exists(brokerLogsPath)) {
                Files.createFile(brokerLogsPath);
                logger.info("Archivo de logs creado: {}", brokerLogsDbPath);
            }
            
        } catch (Exception e) {
            logger.error("Error inicializando archivos de persistencia: {}", e.getMessage());
            throw new RuntimeException("Error inicializando persistencia", e);
        }
    }
    
    /**
     * Carga datos existentes desde archivos.
     */
    private void cargarDatosExistentes() {
        try {
            logger.info("Cargando datos existentes desde archivos...");
            
            // Cargar votos pendientes
            Path votosPendientesPath = Paths.get(votosPendientesDbPath);
            if (Files.exists(votosPendientesPath) && Files.size(votosPendientesPath) > 0) {
                Files.lines(votosPendientesPath).forEach(linea -> {
                    try {
                        if (!linea.trim().isEmpty()) {
                            String[] partes = linea.split("\\|", 4);
                            if (partes.length >= 4) {
                                String votoId = partes[0];
                                String datos = partes[1];
                                String timestamp = partes[2];
                                String estado = partes[3];
                                
                                DatosBroker datosBroker = new DatosBroker(votoId, datos, timestamp, 
                                    Votacion.EstadoVoto.valueOf(estado));
                                cacheVotosPendientes.put(votoId, datosBroker);
                            }
                        }
                    } catch (Exception e) {
                        logger.warn("Error cargando línea de votos pendientes: {}", linea);
                    }
                });
                
                logger.info("Cargados {} votos pendientes desde archivo", cacheVotosPendientes.size());
            }
            
        } catch (Exception e) {
            logger.error("Error cargando datos existentes: {}", e.getMessage());
        }
    }
    
    /**
     * Persiste un voto pendiente al archivo.
     */
    private void persistirVotoPendiente(String votoId, DatosBroker datos) throws IOException {
        String linea = String.format("%s|%s|%s|%s%n", 
            votoId, datos.datos, datos.timestamp, datos.estado.toString());
        
        Files.write(Paths.get(votosPendientesDbPath), linea.getBytes(), 
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    
    /**
     * Persiste un voto enviado (para auditoría).
     */
    private void persistirVotoEnviado(String votoId, DatosBroker datos) throws IOException {
        String logEnviado = String.format("VOTO_ENVIADO|%s|%s|%s%n", 
            votoId, Instant.now().toString(), datos.datos);
        
        Files.write(Paths.get(brokerLogsDbPath), logEnviado.getBytes(), 
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
    
    /**
     * Remueve un voto pendiente del archivo.
     */
    private void removerVotoPendienteDeArchivo(String votoId) throws IOException {
        Path votosPendientesPath = Paths.get(votosPendientesDbPath);
        
        // Leer todas las líneas y filtrar la del voto removido
        java.util.List<String> lineasFiltradas = Files.lines(votosPendientesPath)
            .filter(linea -> !linea.startsWith(votoId + "|"))
            .collect(Collectors.toList());
        
        // Reescribir archivo sin el voto removido
        Files.write(votosPendientesPath, lineasFiltradas);
    }
    
    /**
     * Escribe un log de auditoría.
     */
    private void escribirLogAuditoria(String operacion, String votoId, String detalles) {
        lockLogs.writeLock().lock();
        try {
            String logEntry = String.format("AUDIT|%s|%s|%s|%s%n", 
                Instant.now().toString(), operacion, votoId, detalles);
            
            Files.write(Paths.get(brokerLogsDbPath), logEntry.getBytes(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            
            totalLogsEscritos++;
            
        } catch (Exception e) {
            logger.error("Error escribiendo log de auditoría: {}", e.getMessage());
        } finally {
            lockLogs.writeLock().unlock();
        }
    }
    
    /**
     * Realiza limpieza de recursos.
     */
    public void shutdown() {
        logger.info("Finalizando PersistenceManager...");
        
        // Escribir estadísticas finales
        escribirLogAuditoria("SHUTDOWN", "SISTEMA", getEstadisticasPersistencia());
        
        logger.info("PersistenceManager finalizado");
        logger.info("Estadísticas finales: {}", getEstadisticasPersistencia());
    }
} 