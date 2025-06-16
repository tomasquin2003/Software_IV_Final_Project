package com.registraduria.votacion.database.manager;

import Votacion.CacheException;
import Votacion.CircuitStatus;
import Votacion.ConnectionInfo;
import Votacion.ConnectionManager;
import Votacion.DatabaseNode;
import Votacion.ErrorPersistenciaException;
import Votacion.EstadoVoto;
import Votacion.QueryParams;
import Votacion.QueryResult;
import Votacion.QueryTimeoutException;
import Votacion.DatabaseConnectionException;

import com.registraduria.votacion.database.router.QueryRouterImpl;
import com.registraduria.votacion.database.failover.FailoverHandlerImpl;
import com.registraduria.votacion.database.circuit.CircuitBreakerServiceImpl;
import com.registraduria.votacion.database.cache.CacheServiceImpl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.zeroc.Ice.Current;

/**
 * ConnectionManager - Gateway inteligente y resiliente para acceso a bases de datos.
 * 
 * Punto de entrada principal del DatabaseProxy que coordina todos los componentes:
 * QueryRouter, FailoverHandler, CircuitBreakerService y CacheService.
 * 
 * NOTA: Implementa la interfaz ConnectionManager de DatabaseProxy.ice
 */
public class ConnectionManagerImpl implements ConnectionManager {
    private static final Logger logger = LoggerFactory.getLogger(ConnectionManagerImpl.class);
    
    // Componentes del DatabaseProxy
    private final QueryRouterImpl queryRouter;
    private final FailoverHandlerImpl failoverHandler;
    private final CircuitBreakerServiceImpl circuitBreakerService;
    private final CacheServiceImpl cacheService;
    
    // Cache local de resultados frecuentes
    private final ConcurrentHashMap<String, String> resultadosCache;
    
    // Métricas del sistema
    private volatile AtomicLong totalOperaciones = new AtomicLong(0);
    private volatile AtomicLong totalVotosGuardados = new AtomicLong(0);
    private volatile AtomicLong totalConsultas = new AtomicLong(0);
    private volatile AtomicLong totalErrores = new AtomicLong(0);
    
    /**
     * Constructor del ConnectionManager.
     */
    public ConnectionManagerImpl(String dataDir) {
        this.resultadosCache = new ConcurrentHashMap<>();
        
        // Inicializar componentes
        this.cacheService = new CacheServiceImpl(dataDir);
        this.circuitBreakerService = new CircuitBreakerServiceImpl(dataDir);
        this.failoverHandler = new FailoverHandlerImpl(circuitBreakerService, dataDir);
        this.queryRouter = new QueryRouterImpl(failoverHandler, dataDir);
        
        logger.info("ConnectionManager inicializado");
        logger.info("Directorio de datos: {}", dataDir);
        logger.info("Componentes: QueryRouter, FailoverHandler, CircuitBreaker, Cache");
    }
    
    // === MÉTODOS DE LA INTERFAZ DATABASE PROXY ===
    
    /**
     * Guarda un voto en la base de datos (DatabaseProxy.ice).
     */
    public void guardarVoto(String votoId, String candidatoId, String timestamp, String hash) 
            throws ErrorPersistenciaException {
        
        logger.info("=== GUARDAR VOTO (DATABASE PROXY) ===");
        logger.info("VotoId: {}, CandidatoId: {}, Timestamp: {}", votoId, candidatoId, timestamp);
        
        try {
            totalOperaciones.incrementAndGet();
            
            // Validar parámetros
            validarParametrosVoto(votoId, candidatoId, timestamp, hash);
            
            // Crear consulta de inserción
            QueryParams query = new QueryParams();
            query.query = "INSERT INTO votos (voto_id, candidato_id, timestamp, hash) VALUES (?, ?, ?, ?)";
            query.parametros = String.format("%s|%s|%s|%s", votoId, candidatoId, timestamp, hash);
            query.tipo = Votacion.TipoConsulta.INSERT;
            query.timeout = 5000;
            
            // Ejecutar a través del router (sin Current)
            QueryResult resultado = routeQuery(query);
            
            if (!resultado.exitoso) {
                throw new ErrorPersistenciaException("Error guardando voto: " + resultado.mensaje);
            }
            
            totalVotosGuardados.incrementAndGet();
            
            // Invalidar cache relacionado
            invalidarCacheVotos();
            
            logger.info("Voto {} guardado exitosamente en database", votoId);
            
        } catch (ErrorPersistenciaException e) {
            totalErrores.incrementAndGet();
            logger.error("Error guardando voto {}: {}", votoId, e.getMessage());
            throw e;
        } catch (Exception e) {
            totalErrores.incrementAndGet();
            logger.error("Error inesperado guardando voto {}: {}", votoId, e.getMessage());
            throw new ErrorPersistenciaException("Error inesperado: " + e.getMessage());
        }
    }
    
    /**
     * Guarda un voto en la base de datos (DatabaseProxy.ice) - Versión con Current.
     */
    public void guardarVoto(String votoId, String candidatoId, String timestamp, String hash, Current current) 
            throws ErrorPersistenciaException {
        // Delegar a la versión sin Current
        guardarVoto(votoId, candidatoId, timestamp, hash);
    }
    
    /**
     * Verifica el estado de un voto (DatabaseProxy.ice).
     */
    public EstadoVoto verificarEstadoVoto(String votoId) 
            throws ErrorPersistenciaException {
        
        logger.debug("=== VERIFICAR ESTADO VOTO ===");
        logger.debug("VotoId: {}", votoId);
        
        try {
            totalOperaciones.incrementAndGet();
            totalConsultas.incrementAndGet();
            
            if (votoId == null || votoId.trim().isEmpty()) {
                throw new ErrorPersistenciaException("VotoId no puede ser nulo o vacío");
            }
            
            // Verificar cache primero
            String cacheKey = "estado_voto_" + votoId;
            try {
                String estadoCache = getCachedResult(cacheKey);
                if (estadoCache != null && !estadoCache.isEmpty()) {
                    logger.debug("Estado encontrado en cache para voto {}: {}", votoId, estadoCache);
                    return EstadoVoto.valueOf(estadoCache);
                }
            } catch (Exception e) {
                logger.debug("Cache miss para estado de voto {}", votoId);
            }
            
            // Crear consulta de selección
            QueryParams query = new QueryParams();
            query.query = "SELECT estado FROM votos WHERE voto_id = ?";
            query.parametros = votoId;
            query.tipo = Votacion.TipoConsulta.SELECT;
            query.timeout = 3000;
            
            // Ejecutar consulta (sin Current)
            QueryResult resultado = routeQuery(query);
            
            if (!resultado.exitoso) {
                logger.warn("Error consultando estado de voto {}: {}", votoId, resultado.mensaje);
                return EstadoVoto.ERROR;
            }
            
            EstadoVoto estado;
            if (resultado.filasAfectadas == 0 || resultado.datos == null || resultado.datos.isEmpty()) {
                estado = EstadoVoto.ERROR; // Voto no encontrado
            } else {
                try {
                    estado = EstadoVoto.valueOf(resultado.datos.trim());
                } catch (IllegalArgumentException e) {
                    logger.warn("Estado inválido para voto {}: {}", votoId, resultado.datos);
                    estado = EstadoVoto.ERROR;
                }
            }
            
            // Cachear resultado por 30 segundos
            try {
                setCachedResult(cacheKey, estado.name(), 30);
            } catch (Exception e) {
                logger.debug("Error cacheando estado de voto: {}", e.getMessage());
            }
            
            logger.debug("Estado de voto {}: {}", votoId, estado);
            return estado;
            
        } catch (ErrorPersistenciaException e) {
            totalErrores.incrementAndGet();
            throw e;
        } catch (Exception e) {
            totalErrores.incrementAndGet();
            logger.error("Error inesperado verificando estado de voto {}: {}", votoId, e.getMessage());
            throw new ErrorPersistenciaException("Error inesperado: " + e.getMessage());
        }
    }
    
    /**
     * Verifica el estado de un voto (DatabaseProxy.ice) - Versión con Current.
     */
    public EstadoVoto verificarEstadoVoto(String votoId, Current current) 
            throws ErrorPersistenciaException {
        // Delegar a la versión sin Current
        return verificarEstadoVoto(votoId);
    }
    
    /**
     * Guarda candidatos en la base de datos (DatabaseProxy.ice).
     */
    public void guardarCandidatos() 
            throws ErrorPersistenciaException {
        
        logger.info("=== GUARDAR CANDIDATOS (DATABASE PROXY) ===");
        
        try {
            totalOperaciones.incrementAndGet();
            
            // Crear consulta de inserción para candidatos
            QueryParams query = new QueryParams();
            query.query = "INSERT INTO candidatos (candidato_id, nombre, partido, cargo, activo) " +
                         "SELECT * FROM candidatos_temp WHERE NOT EXISTS " +
                         "(SELECT 1 FROM candidatos WHERE candidato_id = candidatos_temp.candidato_id)";
            query.parametros = "";
            query.tipo = Votacion.TipoConsulta.INSERT;
            query.timeout = 10000;
            
            // Ejecutar a través del router (sin Current)
            QueryResult resultado = routeQuery(query);
            
            if (!resultado.exitoso) {
                throw new ErrorPersistenciaException("Error guardando candidatos: " + resultado.mensaje);
            }
            
            // Invalidar cache relacionado
            invalidarCacheCandidatos();
            
            logger.info("Candidatos guardados exitosamente - {} registros", resultado.filasAfectadas);
            
        } catch (ErrorPersistenciaException e) {
            totalErrores.incrementAndGet();
            logger.error("Error guardando candidatos: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            totalErrores.incrementAndGet();
            logger.error("Error inesperado guardando candidatos: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error inesperado: " + e.getMessage());
        }
    }
    
    /**
     * Guarda candidatos en la base de datos (DatabaseProxy.ice) - Versión con Current.
     */
    public void guardarCandidatos(Current current) 
            throws ErrorPersistenciaException {
        // Delegar a la versión sin Current
        guardarCandidatos();
    }
    
    /**
     * Obtiene la lista de candidatos (DatabaseProxy.ice).
     */
    public String obtenerCandidatos() 
            throws ErrorPersistenciaException {
        
        logger.info("=== OBTENER CANDIDATOS ===");
        
        try {
            totalOperaciones.incrementAndGet();
            totalConsultas.incrementAndGet();
            
            // Verificar cache de candidatos (TTL largo)
            String cacheKey = "candidatos_lista";
            try {
                String candidatosCache = getCachedResult(cacheKey);
                if (candidatosCache != null && !candidatosCache.isEmpty()) {
                    logger.info("Candidatos obtenidos desde cache");
                    return candidatosCache;
                }
            } catch (Exception e) {
                logger.debug("Cache miss para candidatos");
            }
            
            // Crear consulta de candidatos
            QueryParams query = new QueryParams();
            query.query = "SELECT candidato_id, nombre, partido, cargo FROM candidatos WHERE activo = true";
            query.parametros = "";
            query.tipo = Votacion.TipoConsulta.SELECT;
            query.timeout = 5000;
            
            // Ejecutar consulta (sin Current)
            QueryResult resultado = routeQuery(query);
            
            if (!resultado.exitoso) {
                throw new ErrorPersistenciaException("Error obteniendo candidatos: " + resultado.mensaje);
            }
            
            String candidatos = resultado.datos != null ? resultado.datos : "[]";
            
            // Cachear por 5 minutos
            try {
                setCachedResult(cacheKey, candidatos, 300);
            } catch (Exception e) {
                logger.debug("Error cacheando candidatos: {}", e.getMessage());
            }
            
            logger.info("Candidatos obtenidos exitosamente: {} bytes", candidatos.length());
            return candidatos;
            
        } catch (ErrorPersistenciaException e) {
            totalErrores.incrementAndGet();
            throw e;
        } catch (Exception e) {
            totalErrores.incrementAndGet();
            logger.error("Error inesperado obteniendo candidatos: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error inesperado: " + e.getMessage());
        }
    }
    
    /**
     * Registra trigger de actualización (DatabaseProxy.ice).
     */
    public void registrarTriggerActualizacion() 
            throws ErrorPersistenciaException {
        
        logger.info("=== REGISTRAR TRIGGER ACTUALIZACION ===");
        
        try {
            totalOperaciones.incrementAndGet();
            
            // Registrar timestamp de trigger
            String timestamp = Instant.now().toString();
            resultadosCache.put("ultimo_trigger", timestamp);
            
            // Invalidar cache de resultados
            invalidarCacheResultados();
            
            logger.info("Trigger de actualización registrado: {}", timestamp);
            
        } catch (Exception e) {
            totalErrores.incrementAndGet();
            logger.error("Error registrando trigger: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error registrando trigger: " + e.getMessage());
        }
    }
    
    /**
     * Obtiene resultados actualizados (DatabaseProxy.ice).
     */
    public String obtenerResultadosActualizados() 
            throws ErrorPersistenciaException {
        
        logger.info("=== OBTENER RESULTADOS ACTUALIZADOS ===");
        
        try {
            totalOperaciones.incrementAndGet();
            totalConsultas.incrementAndGet();
            
            // Crear consulta de resultados
            QueryParams query = new QueryParams();
            query.query = "SELECT candidato_id, COUNT(*) as votos FROM votos GROUP BY candidato_id ORDER BY votos DESC";
            query.parametros = "";
            query.tipo = Votacion.TipoConsulta.SELECT;
            query.timeout = 10000;
            
            // Ejecutar consulta (sin Current)
            QueryResult resultado = routeQuery(query);
            
            if (!resultado.exitoso) {
                throw new ErrorPersistenciaException("Error obteniendo resultados: " + resultado.mensaje);
            }
            
            String resultados = resultado.datos != null ? resultado.datos : "[]";
            
            logger.info("Resultados actualizados obtenidos: {} bytes", resultados.length());
            return resultados;
            
        } catch (ErrorPersistenciaException e) {
            totalErrores.incrementAndGet();
            throw e;
        } catch (Exception e) {
            totalErrores.incrementAndGet();
            logger.error("Error inesperado obteniendo resultados: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error inesperado: " + e.getMessage());
        }
    }
    
    /**
     * Obtiene porcentaje de votación (DatabaseProxy.ice).
     */
    public double obtenerPorcentajeVotacion() 
            throws ErrorPersistenciaException {
        
        logger.info("=== OBTENER PORCENTAJE VOTACION ===");
        
        try {
            totalOperaciones.incrementAndGet();
            totalConsultas.incrementAndGet();
            
            // Verificar cache de porcentaje
            String cacheKey = "porcentaje_votacion";
            try {
                String porcentajeCache = getCachedResult(cacheKey);
                if (porcentajeCache != null && !porcentajeCache.isEmpty()) {
                    logger.debug("Porcentaje obtenido desde cache: {}", porcentajeCache);
                    return Double.parseDouble(porcentajeCache);
                }
            } catch (Exception e) {
                logger.debug("Cache miss para porcentaje de votación");
            }
            
            // Simular cálculo de porcentaje de votación
            QueryParams queryVotos = new QueryParams();
            queryVotos.query = "SELECT COUNT(*) as total_votos FROM votos";
            queryVotos.parametros = "";
            queryVotos.tipo = Votacion.TipoConsulta.SELECT;
            queryVotos.timeout = 5000;
            
            QueryResult resultadoVotos = routeQuery(queryVotos);
            
            if (!resultadoVotos.exitoso) {
                throw new ErrorPersistenciaException("Error consultando total de votos: " + resultadoVotos.mensaje);
            }
            
            // En una implementación real consultaría el censo electoral
            int totalVotos = resultadoVotos.datos != null ? Integer.parseInt(resultadoVotos.datos.trim()) : 0;
            int censoPoblacion = 1000000; // Simular censo
            
            double porcentaje = (totalVotos * 100.0) / censoPoblacion;
            
            // Cachear por 1 minuto
            try {
                setCachedResult(cacheKey, String.valueOf(porcentaje), 60);
            } catch (Exception e) {
                logger.debug("Error cacheando porcentaje: {}", e.getMessage());
            }
            
            logger.info("Porcentaje de votación calculado: {:.2f}%", porcentaje);
            return porcentaje;
            
        } catch (ErrorPersistenciaException e) {
            totalErrores.incrementAndGet();
            throw e;
        } catch (Exception e) {
            totalErrores.incrementAndGet();
            logger.error("Error inesperado calculando porcentaje: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error inesperado: " + e.getMessage());
        }
    }
    
    // === MÉTODOS DE DELEGACIÓN (REQUERIDOS POR LA INTERFAZ) ===
    
    /**
     * Enruta una consulta a través del QueryRouter.
     */
    public QueryResult routeQuery(QueryParams query) 
            throws ErrorPersistenciaException, QueryTimeoutException {
        return queryRouter.routeQuery(query, null);
    }
    
    public CircuitStatus checkCircuitStatus(DatabaseNode dbNode) 
            throws ErrorPersistenciaException {
        return circuitBreakerService.checkCircuitStatus(dbNode, null);
    }
    
    public ConnectionInfo getProxiedConnection(String target) 
            throws ErrorPersistenciaException, DatabaseConnectionException {
        return failoverHandler.getProxiedConnection(target);
    }
    
    public String getCachedResult(String key) throws CacheException {
        return cacheService.getCachedResult(key, null);
    }
    
    public void setCachedResult(String key, String value, int ttl) 
            throws CacheException {
        cacheService.setCachedResult(key, value, ttl, null);
    }
    
    // === MÉTODOS PRIVADOS ===
    
    private void invalidarCacheResultados() {
        try {
            cacheService.invalidateCache("resultados_*", null);
            cacheService.invalidateCache("porcentaje_votacion", null);
        } catch (CacheException e) {
            logger.error("Error invalidando cache de resultados: {}", e.getMessage());
        }
    }
    
    private void invalidarCacheVotos() {
        try {
            cacheService.invalidateCache("votos_*", null);
        } catch (CacheException e) {
            logger.error("Error invalidando cache de votos: {}", e.getMessage());
        }
    }
    
    private void invalidarCacheCandidatos() {
        try {
            cacheService.invalidateCache("candidatos_*", null);
        } catch (CacheException e) {
            logger.error("Error invalidando cache de candidatos: {}", e.getMessage());
        }
    }
    
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
     * Obtiene estadísticas del ConnectionManager.
     */
    public String obtenerEstadisticas() {
        return String.format(
            "ConnectionManager - Operaciones: %d, Votos: %d, Consultas: %d, Errores: %d",
            totalOperaciones.get(), totalVotosGuardados.get(), 
            totalConsultas.get(), totalErrores.get()
        );
    }
    
    /**
     * Finaliza el ConnectionManager.
     */
    public void shutdown() {
        logger.info("Finalizando ConnectionManager...");
        
        if (queryRouter != null) {
            queryRouter.shutdown();
        }
        if (failoverHandler != null) {
            failoverHandler.shutdown();
        }
        if (circuitBreakerService != null) {
            circuitBreakerService.shutdown();
        }
        if (cacheService != null) {
            cacheService.shutdown();
        }
        
        logger.info("ConnectionManager finalizado");
        logger.info("Estadísticas finales: {}", obtenerEstadisticas());
    }
} 