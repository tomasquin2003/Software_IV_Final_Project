#pragma once

#include "Common.ice"

module Votacion {
    // Enums para DatabaseProxy (definidos primero)
    enum TipoConsulta { SELECT, INSERT, UPDATE, DELETE };
    enum EstadoConexion { ACTIVA, INACTIVA, FALLIDA, RECUPERANDO };
    enum TipoCache { TEMPORAL, PERSISTENTE, INVALIDABLE };
    enum EstadoReplicacion { PENDIENTE, SINCRONIZADO, FALLIDO };

    // Estructuras de datos específicas del DatabaseProxy
    struct QueryParams {
        string query;
        string parametros;
        TipoConsulta tipo;
        int timeout;
    };

    struct QueryResult {
        string datos;
        int filasAfectadas;
        bool exitoso;
        string mensaje;
    };

    struct ConnectionInfo {
        string nodoId;
        string host;
        int puerto;
        EstadoConexion estado;
        string ultimaActividad;
    };

    struct CacheEntry {
        string clave;
        string valor;
        string timestamp;
        int ttl;
        TipoCache tipo;
    };

    struct CircuitStatus {
        string nodo;
        bool abierto;
        int fallosConsecutivos;
        string ultimoFallo;
    };

    struct DatabaseNode {
        string nodeId;
        string tipo;
        bool esPrimario;
        EstadoConexion estado;
    };

    struct ResultadosVotacion {
        string candidatoId;
        int votos;
        double porcentaje;
        string timestamp;
    };

    struct TransactionInfo {
        string transactionId;
        string datos;
        string timestamp;
        EstadoReplicacion estado;
    };

    // Excepciones específicas del DatabaseProxy
    exception DatabaseConnectionException {
        string nodo;
        string mensaje;
    };

    exception QueryTimeoutException {
        string query;
        int timeout;
        string mensaje;
    };

    exception ReplicationException {
        string transactionId;
        string mensaje;
    };

    exception CacheException {
        string clave;
        string mensaje;
    };

    // Interfaces del DatabaseProxy

    interface ConnectionManager {
        // Provee
        void guardarVoto(string votoId, string candidatoId, string timestamp, string hash)
            throws ErrorPersistenciaException, DatabaseConnectionException;
        EstadoVoto verificarEstadoVoto(string votoId)
            throws ErrorPersistenciaException, DatabaseConnectionException;
        string obtenerCandidatos()
            throws ErrorPersistenciaException, DatabaseConnectionException;
        void guardarCandidatos()
            throws ErrorPersistenciaException, DatabaseConnectionException;
        void registrarTriggerActualizacion()
            throws ErrorPersistenciaException, DatabaseConnectionException;
        string obtenerResultadosActualizados()
            throws ErrorPersistenciaException, DatabaseConnectionException;
        double obtenerPorcentajeVotacion()
            throws ErrorPersistenciaException, DatabaseConnectionException;
        
        // Requiere
        QueryResult routeQuery(QueryParams query)
            throws ErrorPersistenciaException, QueryTimeoutException;
        CircuitStatus checkCircuitStatus(DatabaseNode dbNode)
            throws ErrorPersistenciaException;
        ConnectionInfo getProxiedConnection(string target)
            throws ErrorPersistenciaException, DatabaseConnectionException;
        string getCachedResult(string key)
            throws CacheException;
        void setCachedResult(string key, string value, int ttl)
            throws CacheException;
    };

    interface QueryRouter {
        // Provee
        QueryResult routeQuery(QueryParams query)
            throws ErrorPersistenciaException, QueryTimeoutException;
        
        // Requiere
        QueryResult executeWrite(QueryParams query)
            throws ErrorPersistenciaException, DatabaseConnectionException;
        QueryResult executeRead(QueryParams query)
            throws ErrorPersistenciaException, DatabaseConnectionException;
    };

    interface FailoverHandler {
        // Provee
        ConnectionInfo getProxiedConnection(string target)
            throws ErrorPersistenciaException, DatabaseConnectionException;
        
        // Requiere
        void registerFailure(string target)
            throws ErrorPersistenciaException;
        void registerSuccess(string target)
            throws ErrorPersistenciaException;
    };

    interface CircuitBreakerService {
        // Provee
        CircuitStatus checkCircuitStatus(DatabaseNode dbNode)
            throws ErrorPersistenciaException;
        void registerFailure(string target)
            throws ErrorPersistenciaException;
        void registerSuccess(string target)
            throws ErrorPersistenciaException;
    };

    interface CacheService {
        // Provee
        string getCachedResult(string key)
            throws CacheException;
        void setCachedResult(string key, string value, int ttl)
            throws CacheException;
        void invalidateCache(string pattern)
            throws CacheException;
    };

    // Interfaces para RDBMS Primary y Replica

    interface RDBMSPrimary {
        // Provee
        QueryResult executeWrite(QueryParams query)
            throws ErrorPersistenciaException, DatabaseConnectionException;
        
        // Requiere
        void replicateData(TransactionInfo transaction)
            throws ReplicationException, ErrorPersistenciaException;
    };

    interface RDBMSReplica {
        // Provee
        QueryResult executeRead(QueryParams query)
            throws ErrorPersistenciaException, DatabaseConnectionException;
        void confirmReplication(string transactionId)
            throws ReplicationException, ErrorPersistenciaException;
    };

    // Interfaces adicionales para completar el diagrama

    interface RepositorioCandidatosLocal {
        // Requiere (remota)
        string obtenerCandidatos()
            throws ErrorPersistenciaException, DatabaseConnectionException;
    };

    interface PublisherResultados {
        // Requiere (remota)
        void registrarTriggerActualizacion()
            throws ErrorPersistenciaException, DatabaseConnectionException;
        string obtenerResultadosActualizados()
            throws ErrorPersistenciaException, DatabaseConnectionException;
        double obtenerPorcentajeVotacion()
            throws ErrorPersistenciaException, DatabaseConnectionException;
    };
};
