package com.registraduria.votacion.database.rdbms;

import Votacion.DatabaseConnectionException;
import Votacion.ErrorPersistenciaException;
import Votacion.QueryParams;
import Votacion.QueryResult;
import Votacion.RDBMSReplica;
import Votacion.ReplicationException;

import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RDBMSReplica - Implementación del adaptador para base de datos réplica.
 * 
 * Maneja operaciones de lectura optimizada y confirmación de replicación.
 */
public class RDBMSReplicaImpl implements RDBMSReplica {
    private static final Logger logger = LoggerFactory.getLogger(RDBMSReplicaImpl.class);
    
    // Archivos de datos replicados
    private final String archivoVotosReplica;
    private final String archivoCandidatosReplica;
    private final String archivoTransaccionesReplica;
    
    // Estado de replicación
    private final ConcurrentHashMap<String, String> transaccionesConfirmadas;
    
    // Métricas
    private volatile AtomicLong totalLecturas = new AtomicLong(0);
    private volatile AtomicLong lecturasExitosas = new AtomicLong(0);
    private volatile AtomicLong lecturasFallidas = new AtomicLong(0);
    private volatile AtomicLong confirmacionesReplicacion = new AtomicLong(0);
    
    public RDBMSReplicaImpl(String dataDir) {
        this.archivoVotosReplica = dataDir + "/VotosDatabase_Replica.db";
        this.archivoCandidatosReplica = dataDir + "/CandidatosDatabase_Replica.db";
        this.archivoTransaccionesReplica = dataDir + "/TransaccionesReplica.log";
        this.transaccionesConfirmadas = new ConcurrentHashMap<>();
        
        logger.info("RDBMSReplica inicializado");
        logger.info("Archivo votos replica: {}", archivoVotosReplica);
        logger.info("Archivo candidatos replica: {}", archivoCandidatosReplica);
        
        inicializarArchivos();
        simularSincronizacionInicial();
    }
    
    @Override
    public QueryResult executeRead(QueryParams query, Current current) 
            throws ErrorPersistenciaException, DatabaseConnectionException {
        
        logger.debug("=== EXECUTE READ (REPLICA) ===");
        logger.debug("Query: {}", query.query);
        
        try {
            totalLecturas.incrementAndGet();
            
            // Verificar conexión a replica
            if (!verificarConexionReplica()) {
                throw new DatabaseConnectionException("REPLICA", "Conexión a replica no disponible");
            }
            
            // Procesar consulta de lectura
            QueryResult resultado = procesarConsultaLectura(query);
            
            if (resultado.exitoso) {
                lecturasExitosas.incrementAndGet();
                escribirLog("READ_SUCCESS", query.query, 
                    String.format("Filas: %d", resultado.filasAfectadas));
            } else {
                lecturasFallidas.incrementAndGet();
                escribirLog("READ_FAILED", query.query, 
                    String.format("Error: %s", resultado.mensaje));
            }
            
            return resultado;
            
        } catch (DatabaseConnectionException e) {
            lecturasFallidas.incrementAndGet();
            throw e;
        } catch (Exception e) {
            lecturasFallidas.incrementAndGet();
            logger.error("Error ejecutando lectura en replica: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error en lectura Replica: " + e.getMessage());
        }
    }
    
    @Override
    public void confirmReplication(String transactionId, Current current) 
            throws ReplicationException, ErrorPersistenciaException {
        
        logger.debug("=== CONFIRM REPLICATION ===");
        logger.debug("TransactionId: {}", transactionId);
        
        try {
            if (transactionId == null || transactionId.trim().isEmpty()) {
                throw new ReplicationException(transactionId, "TransactionId no puede ser nulo");
            }
            
            // Confirmar recepción de transacción
            String timestamp = Instant.now().toString();
            transaccionesConfirmadas.put(transactionId, timestamp);
            confirmacionesReplicacion.incrementAndGet();
            
            // Escribir confirmación
            escribirLog("REPLICATION_CONFIRMED", transactionId, 
                String.format("Confirmado en: %s", timestamp));
            
            // Simular actualización de datos en replica
            simularActualizacionReplica(transactionId);
            
            logger.debug("Replicación confirmada para transacción: {}", transactionId);
            
        } catch (Exception e) {
            logger.error("Error confirmando replicación {}: {}", transactionId, e.getMessage());
            throw new ReplicationException(transactionId, 
                "Error confirmando replicación: " + e.getMessage());
        }
    }
    
    // === MÉTODOS PRIVADOS ===
    
    private void inicializarArchivos() {
        try {
            Files.createDirectories(Paths.get(archivoVotosReplica).getParent());
            
            if (!Files.exists(Paths.get(archivoVotosReplica))) {
                Files.createFile(Paths.get(archivoVotosReplica));
                logger.info("Archivo de votos replica creado: {}", archivoVotosReplica);
            }
            
            if (!Files.exists(Paths.get(archivoCandidatosReplica))) {
                Files.createFile(Paths.get(archivoCandidatosReplica));
                logger.info("Archivo de candidatos replica creado: {}", archivoCandidatosReplica);
            }
            
            if (!Files.exists(Paths.get(archivoTransaccionesReplica))) {
                Files.createFile(Paths.get(archivoTransaccionesReplica));
                logger.info("Archivo de transacciones replica creado: {}", archivoTransaccionesReplica);
            }
            
            escribirLog("INIT", "SISTEMA", "RDBMSReplica inicializado");
            
        } catch (Exception e) {
            logger.error("Error inicializando archivos replica: {}", e.getMessage());
        }
    }
    
    private boolean verificarConexionReplica() {
        // Simular verificación de conexión a BD replica
        // En implementación real haría ping a PostgreSQL/MySQL replica
        return Math.random() > 0.05; // 95% de disponibilidad
    }
    
    private QueryResult procesarConsultaLectura(QueryParams query) {
        QueryResult resultado = new QueryResult();
        resultado.exitoso = false;
        resultado.filasAfectadas = 0;
        resultado.datos = "";
        resultado.mensaje = "";
        
        try {
            String sql = query.query.toLowerCase();
            
            if (sql.contains("select") && sql.contains("votos")) {
                // Consulta de votos desde replica
                resultado = consultarVotosReplica(query);
                
            } else if (sql.contains("select") && sql.contains("candidatos")) {
                // Consulta de candidatos desde replica
                resultado = consultarCandidatosReplica(query);
                
            } else if (sql.contains("count")) {
                // Consulta de conteo
                resultado = procesarConteo(query);
                
            } else {
                // Consulta genérica
                resultado.exitoso = true;
                resultado.filasAfectadas = 0;
                resultado.datos = "[]";
                resultado.mensaje = "Consulta simulada desde replica";
            }
            
        } catch (Exception e) {
            resultado.exitoso = false;
            resultado.mensaje = "Error procesando consulta en replica: " + e.getMessage();
            logger.error("Error procesando consulta en replica: {}", e.getMessage());
        }
        
        return resultado;
    }
    
    private QueryResult consultarVotosReplica(QueryParams query) throws IOException {
        QueryResult resultado = new QueryResult();
        
        if (Files.exists(Paths.get(archivoVotosReplica))) {
            StringBuilder datos = new StringBuilder();
            
            // Leer votos desde archivo replica
            Files.lines(Paths.get(archivoVotosReplica)).forEach(linea -> {
                if (!linea.trim().isEmpty()) {
                    datos.append(linea).append("\n");
                }
            });
            
            long totalLineas = Files.lines(Paths.get(archivoVotosReplica)).count();
            
            resultado.exitoso = true;
            resultado.filasAfectadas = (int) totalLineas;
            resultado.datos = datos.toString();
            resultado.mensaje = "Consulta de votos desde replica exitosa";
        } else {
            resultado.exitoso = true;
            resultado.filasAfectadas = 0;
            resultado.datos = "";
            resultado.mensaje = "No hay datos de votos en replica";
        }
        
        return resultado;
    }
    
    private QueryResult consultarCandidatosReplica(QueryParams query) throws IOException {
        QueryResult resultado = new QueryResult();
        
        // Simular datos de candidatos optimizados para lectura
        String candidatosData = 
            "[{\"id\":\"C001\",\"nombre\":\"Juan Pérez\",\"partido\":\"Partido A\"}," +
            "{\"id\":\"C002\",\"nombre\":\"María González\",\"partido\":\"Partido B\"}," +
            "{\"id\":\"C003\",\"nombre\":\"Carlos López\",\"partido\":\"Partido C\"}]";
        
        resultado.exitoso = true;
        resultado.filasAfectadas = 3;
        resultado.datos = candidatosData;
        resultado.mensaje = "Consulta de candidatos desde replica exitosa";
        
        return resultado;
    }
    
    private QueryResult procesarConteo(QueryParams query) throws IOException {
        QueryResult resultado = new QueryResult();
        
        if (query.query.toLowerCase().contains("votos")) {
            // Contar votos en replica
            if (Files.exists(Paths.get(archivoVotosReplica))) {
                long totalVotos = Files.lines(Paths.get(archivoVotosReplica)).count();
                resultado.datos = String.valueOf(totalVotos);
            } else {
                resultado.datos = "0";
            }
        } else {
            // Conteo genérico
            resultado.datos = "100";
        }
        
        resultado.exitoso = true;
        resultado.filasAfectadas = 1;
        resultado.mensaje = "Conteo desde replica exitoso";
        
        return resultado;
    }
    
    private void simularSincronizacionInicial() {
        try {
            // Simular copia inicial de datos desde Primary
            String datosIniciales = String.format("SYNC_INITIAL|%s|Sincronización inicial de replica%n", 
                Instant.now().toString());
            
            Files.write(Paths.get(archivoVotosReplica), datosIniciales.getBytes(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            
            escribirLog("INITIAL_SYNC", "SISTEMA", "Sincronización inicial completada");
            logger.info("Sincronización inicial de replica completada");
            
        } catch (Exception e) {
            logger.error("Error en sincronización inicial: {}", e.getMessage());
        }
    }
    
    private void simularActualizacionReplica(String transactionId) {
        try {
            // Simular aplicación de cambios en replica
            String actualizacion = String.format("REPLICA_UPDATE|%s|%s|Transacción aplicada%n", 
                transactionId, Instant.now().toString());
            
            Files.write(Paths.get(archivoVotosReplica), actualizacion.getBytes(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            
            escribirLog("REPLICA_UPDATE", transactionId, "Cambios aplicados en replica");
            
        } catch (Exception e) {
            logger.error("Error actualizando replica: {}", e.getMessage());
        }
    }
    
    private void escribirLog(String operacion, String transactionId, String detalles) {
        try {
            String logEntry = String.format("REPLICA|%s|%s|%s|%s%n", 
                Instant.now().toString(), operacion, transactionId, detalles);
            
            Files.write(Paths.get(archivoTransaccionesReplica), logEntry.getBytes(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            
        } catch (Exception e) {
            logger.error("Error escribiendo log de Replica: {}", e.getMessage());
        }
    }
    
    /**
     * Obtiene el estado de replicación.
     */
    public String obtenerEstadoReplicacion() {
        return String.format(
            "Estado Replicación - Confirmadas: %d, Lag estimado: %dms",
            confirmacionesReplicacion.get(), 
            Math.round(Math.random() * 500) // Simular lag
        );
    }
    
    public String obtenerEstadisticas() {
        return String.format(
            "RDBMSReplica - Lecturas: %d, Exitosas: %d, Fallidas: %d, Confirmaciones: %d",
            totalLecturas.get(), lecturasExitosas.get(), 
            lecturasFallidas.get(), confirmacionesReplicacion.get()
        );
    }
    
    public void shutdown() {
        logger.info("Finalizando RDBMSReplica...");
        
        escribirLog("SHUTDOWN", "SISTEMA", obtenerEstadisticas());
        
        logger.info("RDBMSReplica finalizado");
        logger.info("Estadísticas finales: {}", obtenerEstadisticas());
        logger.info("Estado replicación: {}", obtenerEstadoReplicacion());
    }
} 