package com.registraduria.votacion.database.router;

import Votacion.DatabaseConnectionException;
import Votacion.ErrorPersistenciaException;
import Votacion.QueryParams;
import Votacion.QueryResult;
import Votacion.QueryRouter;
import Votacion.QueryTimeoutException;
import Votacion.TipoConsulta;

import com.registraduria.votacion.database.failover.FailoverHandlerImpl;
import com.registraduria.votacion.database.rdbms.RDBMSPrimaryImpl;
import com.registraduria.votacion.database.rdbms.RDBMSReplicaImpl;

import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * QueryRouter - Enruta consultas y escrituras a RDBMS Primary o Replica.
 * 
 * Decide inteligentemente dónde ejecutar cada operación según el tipo:
 * - Escrituras (INSERT, UPDATE, DELETE) → RDBMS Primary
 * - Lecturas (SELECT) → RDBMS Replica (con fallback a Primary)
 */
public class QueryRouterImpl implements QueryRouter {
    private static final Logger logger = LoggerFactory.getLogger(QueryRouterImpl.class);
    
    // Componentes de base de datos
    private final RDBMSPrimaryImpl rdbmsPrimary;
    private final RDBMSReplicaImpl rdbmsReplica;
    private final FailoverHandlerImpl failoverHandler;
    
    // Métricas
    private volatile AtomicLong totalConsultas = new AtomicLong(0);
    private volatile AtomicLong consultasLectura = new AtomicLong(0);
    private volatile AtomicLong consultasEscritura = new AtomicLong(0);
    private volatile AtomicLong fallosEnrutamiento = new AtomicLong(0);
    
    /**
     * Constructor del QueryRouter.
     */
    public QueryRouterImpl(FailoverHandlerImpl failoverHandler, String dataDir) {
        this.failoverHandler = failoverHandler;
        this.rdbmsPrimary = new RDBMSPrimaryImpl(dataDir);
        this.rdbmsReplica = new RDBMSReplicaImpl(dataDir);
        
        logger.info("QueryRouter inicializado");
        logger.info("RDBMS Primary: {}", rdbmsPrimary != null ? "OK" : "ERROR");
        logger.info("RDBMS Replica: {}", rdbmsReplica != null ? "OK" : "ERROR");
    }
    
    /**
     * Enruta una consulta al nodo de base de datos apropiado.
     */
    @Override
    public QueryResult routeQuery(QueryParams query, Current current) 
            throws ErrorPersistenciaException, QueryTimeoutException {
        
        logger.debug("=== ROUTE QUERY ===");
        logger.debug("Tipo: {}, Query: {}", query.tipo, query.query);
        
        try {
            totalConsultas.incrementAndGet();
            
            // Validar parámetros
            validarQuery(query);
            
            // Verificar timeout
            if (query.timeout > 0 && query.timeout < 1000) {
                throw new QueryTimeoutException(query.query, query.timeout, "Timeout muy bajo para la consulta");
            }
            
            QueryResult resultado;
            
            if (esOperacionEscritura(query.tipo)) {
                // Operaciones de escritura van al Primary
                consultasEscritura.incrementAndGet();
                logger.debug("Enrutando escritura a RDBMS Primary");
                resultado = executeWrite(query, current);
                
                // Intentar replicación asíncrona
                intentarReplicacion(query);
            } else {
                // Operaciones de lectura van al Replica (con fallback)
                consultasLectura.incrementAndGet();
                logger.debug("Enrutando lectura a RDBMS Replica");
                resultado = executeRead(query, current);
            }
            
            logger.debug("Query ejecutada exitosamente: {} filas afectadas", resultado.filasAfectadas);
            return resultado;
            
        } catch (ErrorPersistenciaException | QueryTimeoutException e) {
            fallosEnrutamiento.incrementAndGet();
            logger.error("Error enrutando query: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            fallosEnrutamiento.incrementAndGet();
            logger.error("Error inesperado enrutando query: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error inesperado en enrutamiento: " + e.getMessage());
        }
    }
    
    /**
     * Ejecuta una operación de escritura en el RDBMS Primary.
     */
    @Override
    public QueryResult executeWrite(QueryParams query, Current current) 
            throws ErrorPersistenciaException, DatabaseConnectionException {
        
        logger.debug("=== EXECUTE WRITE ===");
        logger.debug("Query: {}", query.query);
        
        try {
            // Verificar estado del Primary
            if (!verificarEstadoPrimary()) {
                throw new DatabaseConnectionException("RDBMS_PRIMARY", 
                    "RDBMS Primary no disponible para escritura");
            }
            
            // Verificar timeout antes de ejecutar (convertir a ErrorPersistenciaException)
            if (query.timeout > 0 && query.timeout > 30000) {
                throw new ErrorPersistenciaException("Timeout excesivo para escritura: " + query.timeout + "ms");
            }
            
            // Ejecutar en Primary
            QueryResult resultado = rdbmsPrimary.executeWrite(query, current);
            
            // Registrar éxito en failover
            failoverHandler.registerSuccess("RDBMS_PRIMARY");
            
            logger.debug("Escritura ejecutada exitosamente en PRIMARY");
            return resultado;
            
        } catch (DatabaseConnectionException e) {
            // Registrar fallo en failover handler
            failoverHandler.registerFailure("RDBMS_PRIMARY");
            
            logger.error("Error de conexión en PRIMARY: {}", e.getMessage());
            throw e;
        } catch (ErrorPersistenciaException e) {
            failoverHandler.registerFailure("RDBMS_PRIMARY");
            throw e;
        } catch (Exception e) {
            failoverHandler.registerFailure("RDBMS_PRIMARY");
            logger.error("Error inesperado en escritura PRIMARY: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error en escritura PRIMARY: " + e.getMessage());
        }
    }
    
    /**
     * Ejecuta una operación de lectura en el RDBMS Replica (con fallback).
     */
    @Override
    public QueryResult executeRead(QueryParams query, Current current) 
            throws ErrorPersistenciaException, DatabaseConnectionException {
        
        logger.debug("=== EXECUTE READ ===");
        logger.debug("Query: {}", query.query);
        
        try {
            // Intentar primero en Replica
            if (verificarEstadoReplica()) {
                try {
                    QueryResult resultado = rdbmsReplica.executeRead(query, current);
                    
                    // Registrar éxito
                    failoverHandler.registerSuccess("RDBMS_REPLICA");
                    
                    logger.debug("Lectura ejecutada exitosamente en REPLICA");
                    return resultado;
                } catch (Exception e) {
                    logger.warn("Error ejecutando en Replica: {}", e.getMessage());
                    failoverHandler.registerFailure("RDBMS_REPLICA");
                }
            }
            
            // Fallback al Primary
            logger.debug("Ejecutando fallback de lectura a Primary");
            
            if (!verificarEstadoPrimary()) {
                throw new DatabaseConnectionException("RDBMS_ALL", 
                    "Ni Primary ni Replica disponibles para lectura");
            }
            
            // Convertir query a formato de escritura para Primary
            QueryResult resultado = rdbmsPrimary.executeWrite(query, current);
            
            // Registrar éxito en PRIMARY
            failoverHandler.registerSuccess("RDBMS_PRIMARY");
            
            logger.info("Lectura fallback ejecutada exitosamente en PRIMARY");
            return resultado;
            
        } catch (Exception fallbackException) {
            failoverHandler.registerFailure("RDBMS_PRIMARY");
            logger.error("Error en fallback PRIMARY: {}", fallbackException.getMessage());
            throw new ErrorPersistenciaException("Error en todas las bases de datos: " + fallbackException.getMessage());
        }
    }
    
    // === MÉTODOS PRIVADOS ===
    
    /**
     * Valida los parámetros de la consulta.
     */
    private void validarQuery(QueryParams query) throws ErrorPersistenciaException {
        if (query == null) {
            throw new ErrorPersistenciaException("QueryParams no puede ser nulo");
        }
        if (query.query == null || query.query.trim().isEmpty()) {
            throw new ErrorPersistenciaException("Query SQL no puede ser nula o vacía");
        }
        if (query.tipo == null) {
            throw new ErrorPersistenciaException("Tipo de consulta no puede ser nulo");
        }
        if (query.timeout <= 0) {
            query.timeout = 5000; // Timeout por defecto
        }
    }
    
    /**
     * Determina si una operación es de escritura.
     */
    private boolean esOperacionEscritura(TipoConsulta tipo) {
        return tipo == TipoConsulta.INSERT || 
               tipo == TipoConsulta.UPDATE || 
               tipo == TipoConsulta.DELETE;
    }
    
    /**
     * Verifica el estado del RDBMS Primary.
     */
    private boolean verificarEstadoPrimary() {
        try {
            // En implementación real verificaría la conexión
            // Por ahora, simulamos que siempre está disponible
            return true;
        } catch (Exception e) {
            logger.error("Error verificando estado Primary: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Verifica el estado del RDBMS Replica.
     */
    private boolean verificarEstadoReplica() {
        try {
            // En implementación real verificaría la conexión
            // Simulamos disponibilidad del 90%
            return Math.random() > 0.1;
        } catch (Exception e) {
            logger.error("Error verificando estado Replica: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Intenta replicar una operación de escritura.
     */
    private void intentarReplicacion(QueryParams query) {
        try {
            if (verificarEstadoReplica()) {
                // En implementación real enviaría los datos al Replica
                logger.debug("Replicación simulada para query: {}", query.query);
                
                // Confirmar replicación
                rdbmsReplica.confirmReplication("TXN_" + System.currentTimeMillis(), null);
            } else {
                logger.warn("Replica no disponible para replicación");
            }
        } catch (Exception e) {
            logger.warn("Error en replicación: {}", e.getMessage());
            // No fallar la operación principal por errores de replicación
        }
    }
    
    /**
     * Obtiene estadísticas del QueryRouter.
     */
    public String obtenerEstadisticas() {
        return String.format(
            "QueryRouter - Total: %d, Lectura: %d, Escritura: %d, Fallos: %d",
            totalConsultas.get(), consultasLectura.get(), 
            consultasEscritura.get(), fallosEnrutamiento.get()
        );
    }
    
    /**
     * Finaliza el QueryRouter.
     */
    public void shutdown() {
        logger.info("Finalizando QueryRouter...");
        
        if (rdbmsPrimary != null) {
            rdbmsPrimary.shutdown();
        }
        if (rdbmsReplica != null) {
            rdbmsReplica.shutdown();
        }
        
        logger.info("QueryRouter finalizado");
        logger.info("Estadísticas finales: {}", obtenerEstadisticas());
    }
} 