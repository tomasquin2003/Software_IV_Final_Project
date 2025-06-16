package com.registraduria.votacion.database.rdbms;

import Votacion.DatabaseConnectionException;
import Votacion.ErrorPersistenciaException;
import Votacion.QueryParams;
import Votacion.QueryResult;
import Votacion.RDBMSPrimary;
import Votacion.ReplicationException;
import Votacion.TransactionInfo;

import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RDBMSPrimary - Implementación del adaptador para base de datos principal.
 * 
 * Maneja todas las operaciones de escritura y actúa como fuente de verdad.
 */
public class RDBMSPrimaryImpl implements RDBMSPrimary {
    private static final Logger logger = LoggerFactory.getLogger(RDBMSPrimaryImpl.class);
    
    // Archivos de datos simulados
    private final String archivoVotos;
    private final String archivoCandidatos;
    private final String archivoTransacciones;
    
    // Métricas
    private volatile AtomicLong totalEscrituras = new AtomicLong(0);
    private volatile AtomicLong transaccionesExitosas = new AtomicLong(0);
    private volatile AtomicLong transaccionesFallidas = new AtomicLong(0);
    
    public RDBMSPrimaryImpl(String dataDir) {
        this.archivoVotos = dataDir + "/VotosDatabase.db";
        this.archivoCandidatos = dataDir + "/CandidatosDatabase.db";
        this.archivoTransacciones = dataDir + "/TransaccionesPrimary.log";
        
        logger.info("RDBMSPrimary inicializado");
        logger.info("Archivo votos: {}", archivoVotos);
        logger.info("Archivo candidatos: {}", archivoCandidatos);
        
        inicializarArchivos();
    }
    
    @Override
    public QueryResult executeWrite(QueryParams query, Current current) 
            throws ErrorPersistenciaException, DatabaseConnectionException {
        
        logger.debug("=== EXECUTE WRITE (PRIMARY) ===");
        logger.debug("Query: {}", query.query);
        
        try {
            totalEscrituras.incrementAndGet();
            
            // Validar conexión
            if (!verificarConexion()) {
                throw new DatabaseConnectionException("PRIMARY", "Conexión no disponible");
            }
            
            // Procesar según tipo de operación
            QueryResult resultado = procesarOperacion(query);
            
            if (resultado.exitoso) {
                transaccionesExitosas.incrementAndGet();
                
                // Crear transacción para replicación
                TransactionInfo transaccion = crearTransaccion(query, resultado);
                replicateData(transaccion, current);
                
                escribirLog("WRITE_SUCCESS", query.query, 
                    String.format("Filas: %d", resultado.filasAfectadas));
            } else {
                transaccionesFallidas.incrementAndGet();
                escribirLog("WRITE_FAILED", query.query, 
                    String.format("Error: %s", resultado.mensaje));
            }
            
            return resultado;
            
        } catch (DatabaseConnectionException e) {
            transaccionesFallidas.incrementAndGet();
            throw e;
        } catch (Exception e) {
            transaccionesFallidas.incrementAndGet();
            logger.error("Error ejecutando escritura: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error en escritura Primary: " + e.getMessage());
        }
    }
    
    @Override
    public void replicateData(TransactionInfo transaction, Current current) 
            throws ReplicationException, ErrorPersistenciaException {
        
        logger.debug("=== REPLICATE DATA ===");
        logger.debug("TransactionId: {}", transaction.transactionId);
        
        try {
            // Simular proceso de replicación
            // En implementación real enviaría datos al Replica
            
            escribirLog("REPLICATION_START", transaction.transactionId, 
                String.format("Estado: %s", transaction.estado));
            
            // Simular latencia de red
            Thread.sleep(50);
            
            // Simular éxito del 95%
            boolean exito = Math.random() > 0.05;
            
            if (exito) {
                escribirLog("REPLICATION_SUCCESS", transaction.transactionId, 
                    "Replicación completada");
                logger.debug("Replicación exitosa para transacción: {}", transaction.transactionId);
            } else {
                escribirLog("REPLICATION_FAILED", transaction.transactionId, 
                    "Fallo en replicación");
                throw new ReplicationException(transaction.transactionId, 
                    "Error en proceso de replicación");
            }
            
        } catch (ReplicationException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error en replicación: {}", e.getMessage());
            throw new ReplicationException(transaction.transactionId, 
                "Error inesperado en replicación: " + e.getMessage());
        }
    }
    
    // === MÉTODOS PRIVADOS ===
    
    private void inicializarArchivos() {
        try {
            Files.createDirectories(Paths.get(archivoVotos).getParent());
            
            if (!Files.exists(Paths.get(archivoVotos))) {
                Files.createFile(Paths.get(archivoVotos));
                logger.info("Archivo de votos creado: {}", archivoVotos);
            }
            
            if (!Files.exists(Paths.get(archivoCandidatos))) {
                Files.createFile(Paths.get(archivoCandidatos));
                logger.info("Archivo de candidatos creado: {}", archivoCandidatos);
            }
            
            if (!Files.exists(Paths.get(archivoTransacciones))) {
                Files.createFile(Paths.get(archivoTransacciones));
                logger.info("Archivo de transacciones creado: {}", archivoTransacciones);
            }
            
            escribirLog("INIT", "SISTEMA", "RDBMSPrimary inicializado");
            
        } catch (Exception e) {
            logger.error("Error inicializando archivos: {}", e.getMessage());
        }
    }
    
    private boolean verificarConexion() {
        // Simular verificación de conexión a BD
        // En implementación real haría ping a PostgreSQL/MySQL
        return Math.random() > 0.02; // 98% de disponibilidad
    }
    
    private QueryResult procesarOperacion(QueryParams query) {
        QueryResult resultado = new QueryResult();
        resultado.exitoso = false;
        resultado.filasAfectadas = 0;
        resultado.datos = "";
        resultado.mensaje = "";
        
        try {
            String sql = query.query.toLowerCase();
            
            if (sql.contains("insert into votos")) {
                // Procesar inserción de voto
                resultado = procesarInsercionVoto(query);
                
            } else if (sql.contains("select") && sql.contains("votos")) {
                // Procesar consulta de votos
                resultado = procesarConsultaVotos(query);
                
            } else if (sql.contains("insert into candidatos") || sql.contains("update candidatos")) {
                // Procesar operación de candidatos
                resultado = procesarOperacionCandidatos(query);
                
            } else if (sql.contains("batch_update_candidatos")) {
                // Procesar operación batch
                resultado = procesarOperacionBatch(query);
                
            } else {
                // Operación genérica
                resultado.exitoso = true;
                resultado.filasAfectadas = 1;
                resultado.mensaje = "Operación simulada exitosa";
            }
            
        } catch (Exception e) {
            resultado.exitoso = false;
            resultado.mensaje = "Error procesando operación: " + e.getMessage();
            logger.error("Error procesando operación: {}", e.getMessage());
        }
        
        return resultado;
    }
    
    private QueryResult procesarInsercionVoto(QueryParams query) throws IOException {
        QueryResult resultado = new QueryResult();
        
        // Extraer parámetros del voto
        String[] params = query.parametros.split("\\|");
        if (params.length >= 4) {
            String votoId = params[0];
            String candidatoId = params[1];
            String timestamp = params[2];
            String hash = params[3];
            
            // Escribir en archivo de votos
            String linea = String.format("%s|%s|%s|%s|%s%n", 
                votoId, candidatoId, timestamp, hash, Instant.now().toString());
            
            Files.write(Paths.get(archivoVotos), linea.getBytes(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            
            resultado.exitoso = true;
            resultado.filasAfectadas = 1;
            resultado.mensaje = "Voto insertado exitosamente";
        } else {
            resultado.exitoso = false;
            resultado.mensaje = "Parámetros de voto inválidos";
        }
        
        return resultado;
    }
    
    private QueryResult procesarConsultaVotos(QueryParams query) throws IOException {
        QueryResult resultado = new QueryResult();
        
        if (Files.exists(Paths.get(archivoVotos))) {
            StringBuilder datos = new StringBuilder();
            int contador = 0;
            
            Files.lines(Paths.get(archivoVotos)).forEach(linea -> {
                datos.append(linea).append("\n");
            });
            
            long totalLineas = Files.lines(Paths.get(archivoVotos)).count();
            
            resultado.exitoso = true;
            resultado.filasAfectadas = (int) totalLineas;
            resultado.datos = datos.toString();
            resultado.mensaje = "Consulta de votos exitosa";
        } else {
            resultado.exitoso = true;
            resultado.filasAfectadas = 0;
            resultado.datos = "";
            resultado.mensaje = "No hay datos de votos";
        }
        
        return resultado;
    }
    
    private QueryResult procesarOperacionCandidatos(QueryParams query) throws IOException {
        QueryResult resultado = new QueryResult();
        
        // Simular operación en candidatos
        String operacion = String.format("CANDIDATOS|%s|%s|%s%n", 
            query.query, query.parametros, Instant.now().toString());
        
        Files.write(Paths.get(archivoCandidatos), operacion.getBytes(), 
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        
        resultado.exitoso = true;
        resultado.filasAfectadas = 1;
        resultado.mensaje = "Operación de candidatos exitosa";
        
        return resultado;
    }
    
    private QueryResult procesarOperacionBatch(QueryParams query) {
        QueryResult resultado = new QueryResult();
        
        // Simular operación batch
        resultado.exitoso = true;
        resultado.filasAfectadas = 50; // Simular 50 registros afectados
        resultado.mensaje = "Operación batch completada";
        
        return resultado;
    }
    
    private TransactionInfo crearTransaccion(QueryParams query, QueryResult resultado) {
        TransactionInfo transaccion = new TransactionInfo();
        transaccion.transactionId = "TXN_" + System.currentTimeMillis();
        transaccion.datos = String.format("Query: %s | Params: %s | Result: %s", 
            query.query, query.parametros, resultado.mensaje);
        transaccion.timestamp = Instant.now().toString();
        transaccion.estado = Votacion.EstadoReplicacion.PENDIENTE;
        
        return transaccion;
    }
    
    private void escribirLog(String operacion, String query, String detalles) {
        try {
            String logEntry = String.format("PRIMARY|%s|%s|%s|%s%n", 
                Instant.now().toString(), operacion, query, detalles);
            
            Files.write(Paths.get(archivoTransacciones), logEntry.getBytes(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            
        } catch (Exception e) {
            logger.error("Error escribiendo log de Primary: {}", e.getMessage());
        }
    }
    
    public String obtenerEstadisticas() {
        return String.format(
            "RDBMSPrimary - Escrituras: %d, Exitosas: %d, Fallidas: %d",
            totalEscrituras.get(), transaccionesExitosas.get(), 
            transaccionesFallidas.get()
        );
    }
    
    public void shutdown() {
        logger.info("Finalizando RDBMSPrimary...");
        
        escribirLog("SHUTDOWN", "SISTEMA", obtenerEstadisticas());
        
        logger.info("RDBMSPrimary finalizado");
        logger.info("Estadísticas finales: {}", obtenerEstadisticas());
    }
} 