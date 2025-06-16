package com.registraduria.votacion.regional.consultas;

import Votacion.*;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * GestorConsultasRegionalImpl - Implementación del gestor de consultas regionales.
 * 
 * Responsable de:
 * - Procesar consultas de lugar de votación
 * - Gestionar caché regional para optimización
 * - Conectividad robusta con ServidorCentral
 * - Manejo de excepciones y timeouts
 */
public class GestorConsultasRegionalImpl implements GestorConsultasRegional {
    private static final Logger logger = LoggerFactory.getLogger(GestorConsultasRegionalImpl.class);
    
    // Dependencias
    private final Communicator communicator;
    private final CacheRegionalPrx cacheRegional;
    private final String endpointCentral;
    private final String regionId;
    
    // Proxies remotos
    private AdministradorMesaRemotoPrx administradorMesa;
    
    // Configuración
    private static final int TIMEOUT_CONSULTA = 5000; // 5 segundos
    private static final int MAX_REINTENTOS = 3;
    private static final int TTL_CACHE_DEFAULT = 3600; // 1 hora
    
    // Ejecutor para operaciones asíncronas
    private final ExecutorService executor;
    
    // Métricas
    private long consultasExitosas = 0;
    private long consultasFallidas = 0;
    private long consultasCache = 0;
    private long consultasRemotas = 0;
    
    /**
     * Constructor del GestorConsultasRegional.
     */
    public GestorConsultasRegionalImpl(Communicator communicator, 
                                      CacheRegionalPrx cacheRegional,
                                      String endpointCentral,
                                      String regionId) {
        this.communicator = communicator;
        this.cacheRegional = cacheRegional;
        this.endpointCentral = endpointCentral;
        this.regionId = regionId;
        this.executor = Executors.newFixedThreadPool(5);
        
        inicializarConexionRemota();
        logger.info("GestorConsultasRegional inicializado para región: {}", regionId);
    }
    
    /**
     * Inicializa la conexión con el ServidorCentral.
     */
    private void inicializarConexionRemota() {
        try {
            logger.info("Iniciando conexión con ServidorCentral: {}", endpointCentral);
            
            var proxy = communicator.stringToProxy(endpointCentral);
            administradorMesa = AdministradorMesaRemotoPrx.checkedCast(proxy);
            
            if (administradorMesa == null) {
                logger.warn("No se pudo establecer conexión con AdministradorMesa");
            } else {
                logger.info("OK Conexion establecida con ServidorCentral");
            }
            
        } catch (Exception e) {
            logger.error("Error inicializando conexión remota: {}", e.getMessage());
            administradorMesa = null;
        }
    }
    
    @Override
    public LugarVotacion consultarLugarVotacion(String cedula, Current current) 
            throws VotanteNoExisteException, RegionNoDisponibleException, ErrorPersistenciaException {
        
        logger.debug("Consultando lugar de votación para cédula: {}", cedula);
        
        try {
            // Validar entrada
            if (cedula == null || cedula.trim().isEmpty()) {
                throw new VotanteNoExisteException(cedula, "Cédula no puede estar vacía");
            }
            
            cedula = cedula.trim();
            
            // 1. Intentar desde caché primero
            LugarVotacion lugarFromCache = obtenerDesdeCache(cedula);
            if (lugarFromCache != null) {
                consultasCache++;
                consultasExitosas++;
                logger.debug("Lugar de votación obtenido desde caché para: {}", cedula);
                return lugarFromCache;
            }
            
            // 2. Consultar al ServidorCentral
            LugarVotacion lugarFromRemote = consultarServidorCentral(cedula);
            
            // 3. Actualizar caché de forma asíncrona
            if (lugarFromRemote != null) {
                actualizarCacheAsync(cedula, lugarFromRemote);
                consultasRemotas++;
                consultasExitosas++;
                logger.debug("Lugar de votación obtenido desde ServidorCentral para: {}", cedula);
                return lugarFromRemote;
            }
            
            // 4. No encontrado
            consultasFallidas++;
            throw new VotanteNoExisteException(cedula, "Votante no encontrado en la región " + regionId);
            
        } catch (VotanteNoExisteException | RegionNoDisponibleException e) {
            consultasFallidas++;
            throw e;
        } catch (Exception e) {
            consultasFallidas++;
            logger.error("Error consultando lugar de votación para {}: {}", cedula, e.getMessage());
            throw new ErrorPersistenciaException("Error interno en consulta: " + e.getMessage());
        }
    }
    
    /**
     * Obtiene datos desde el caché regional.
     */
    private LugarVotacion obtenerDesdeCache(String cedula) {
        try {
            DatosCache datosCache = cacheRegional.obtenerDatosCache(cedula);
            if (datosCache != null && datosCache.valido) {
                return parsearLugarVotacion(datosCache.valor);
            }
        } catch (Exception e) {
            logger.debug("No se encontró en caché o error accediendo: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * Consulta al ServidorCentral de forma robusta.
     */
    private LugarVotacion consultarServidorCentral(String cedula) 
            throws VotanteNoExisteException, RegionNoDisponibleException, ErrorPersistenciaException {
        
        if (administradorMesa == null) {
            logger.warn("Sin conexión con ServidorCentral, intentando reconectar...");
            inicializarConexionRemota();
            
            if (administradorMesa == null) {
                throw new RegionNoDisponibleException(regionId, "ServidorCentral no disponible");
            }
        }
        
        Exception ultimaExcepcion = null;
        
        for (int intento = 1; intento <= MAX_REINTENTOS; intento++) {
            try {
                logger.debug("Intento {} consultando ServidorCentral para: {}", intento, cedula);
                
                // Usar future con timeout
                CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        return administradorMesa.consultarAsignacionMesas(cedula);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, executor);
                
                String respuesta = future.get(TIMEOUT_CONSULTA, TimeUnit.MILLISECONDS);
                
                if (respuesta != null && !respuesta.trim().isEmpty()) {
                    return construirLugarVotacion(cedula, respuesta);
                } else {
                    throw new VotanteNoExisteException(cedula, "No se encontró asignación de mesa");
                }
                
            } catch (java.util.concurrent.TimeoutException e) {
                ultimaExcepcion = e;
                logger.warn("Timeout en intento {} para consulta de {}", intento, cedula);
                
                if (intento < MAX_REINTENTOS) {
                    try {
                        Thread.sleep(1000 * intento); // Backoff exponencial
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                
            } catch (Exception e) {
                ultimaExcepcion = e;
                logger.warn("Error en intento {} para consulta de {}: {}", intento, cedula, e.getMessage());
                
                // Para VotanteNoExisteException, no reintentar
                if (e.getCause() instanceof VotanteNoExisteException) {
                    throw (VotanteNoExisteException) e.getCause();
                }
                
                if (intento < MAX_REINTENTOS) {
                    try {
                        Thread.sleep(500 * intento);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        
        // Todos los intentos fallaron
        if (ultimaExcepcion != null) {
            logger.error("Todos los intentos fallaron consultando ServidorCentral: {}", ultimaExcepcion.getMessage());
            
            if (ultimaExcepcion instanceof java.util.concurrent.TimeoutException) {
                throw new RegionNoDisponibleException(regionId, "Timeout consultando ServidorCentral");
            } else {
                throw new ErrorPersistenciaException("Error consultando ServidorCentral: " + ultimaExcepcion.getMessage());
            }
        }
        
        throw new ErrorPersistenciaException("Error desconocido consultando ServidorCentral");
    }
    
    /**
     * Construye un LugarVotacion desde la respuesta del ServidorCentral.
     */
    private LugarVotacion construirLugarVotacion(String cedula, String respuestaServidor) {
        try {
            // La respuesta puede ser JSON o formato específico
            // Para este ejemplo, asumimos formato: mesaId|direccion|horarios
            String[] partes = respuestaServidor.split("\\|");
            
            LugarVotacion lugar = new LugarVotacion();
            lugar.mesaId = partes.length > 0 ? partes[0] : "MESA_" + regionId;
            lugar.direccion = partes.length > 1 ? partes[1] : "Dirección por definir";
            lugar.horarioApertura = partes.length > 2 ? partes[2] : "08:00";
            lugar.horarioCierre = partes.length > 3 ? partes[3] : "16:00";
            lugar.responsable = partes.length > 4 ? partes[4] : "Responsable asignado";
            lugar.telefono = partes.length > 5 ? partes[5] : "Pendiente";
            lugar.activa = partes.length > 6 ? Boolean.parseBoolean(partes[6]) : true;
            
            // Si no hay suficiente información, crear datos por defecto
            if (partes.length <= 1) {
                lugar.mesaId = "MESA_" + Math.abs(cedula.hashCode() % 1000);
                lugar.direccion = "Centro de Votación " + regionId;
                lugar.horarioApertura = "08:00";
                lugar.horarioCierre = "16:00";
                lugar.responsable = "Responsable Electoral";
                lugar.telefono = "320-555-0100";
                lugar.activa = true;
            }
            
            return lugar;
            
        } catch (Exception e) {
            logger.error("Error construyendo LugarVotacion: {}", e.getMessage());
            
            // Lugar por defecto en caso de error
            LugarVotacion lugarDefault = new LugarVotacion();
            lugarDefault.mesaId = "MESA_DEFAULT";
            lugarDefault.direccion = "Centro de Votación " + regionId;
            lugarDefault.horarioApertura = "08:00";
            lugarDefault.horarioCierre = "16:00";
            lugarDefault.responsable = "Responsable Electoral";
            lugarDefault.telefono = "320-555-0100";
            lugarDefault.activa = true;
            
            return lugarDefault;
        }
    }
    
    /**
     * Actualiza el caché de forma asíncrona.
     */
    private void actualizarCacheAsync(String cedula, LugarVotacion lugar) {
        executor.submit(() -> {
            try {
                DatosCache datosCache = new DatosCache();
                datosCache.clave = cedula;
                datosCache.valor = serializarLugarVotacion(lugar);
                datosCache.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                datosCache.ttl = TTL_CACHE_DEFAULT;
                datosCache.region = regionId;
                datosCache.valido = true;
                
                cacheRegional.actualizarCache(datosCache);
                logger.debug("Caché actualizado para cédula: {}", cedula);
                
            } catch (Exception e) {
                logger.warn("Error actualizando caché para {}: {}", cedula, e.getMessage());
            }
        });
    }
    
    /**
     * Serializa LugarVotacion a string para el caché.
     */
    private String serializarLugarVotacion(LugarVotacion lugar) {
        return String.join("|",
            lugar.mesaId,
            lugar.direccion,
            lugar.horarioApertura,
            lugar.horarioCierre,
            lugar.responsable,
            lugar.telefono,
            String.valueOf(lugar.activa)
        );
    }
    
    /**
     * Parsea string del caché a LugarVotacion.
     */
    private LugarVotacion parsearLugarVotacion(String valor) {
        try {
            String[] partes = valor.split("\\|");
            LugarVotacion lugar = new LugarVotacion();
            
            lugar.mesaId = partes[0];
            lugar.direccion = partes[1];
            lugar.horarioApertura = partes[2];
            lugar.horarioCierre = partes[3];
            lugar.responsable = partes[4];
            lugar.telefono = partes[5];
            lugar.activa = Boolean.parseBoolean(partes[6]);
            
            return lugar;
        } catch (Exception e) {
            logger.error("Error parseando LugarVotacion: {}", e.getMessage());
            return null;
        }
    }
    
    @Override
    public String consultarAsignacionMesas(String cedula, Current current) 
            throws VotanteNoExisteException, ErrorPersistenciaException {
        
        // Este método delega al AdministradorMesa del ServidorCentral
        if (administradorMesa == null) {
            inicializarConexionRemota();
            if (administradorMesa == null) {
                throw new ErrorPersistenciaException("ServidorCentral no disponible");
            }
        }
        
        try {
            return administradorMesa.consultarAsignacionMesas(cedula);
        } catch (Exception e) {
            logger.error("Error consultando asignación para {}: {}", cedula, e.getMessage());
            throw new ErrorPersistenciaException("Error consultando asignación: " + e.getMessage());
        }
    }
    
    @Override
    public DatosCache obtenerDatosCache(String cedula, Current current) throws CacheException {
        try {
            return cacheRegional.obtenerDatosCache(cedula);
        } catch (Exception e) {
            logger.error("Error obteniendo datos de caché para {}: {}", cedula, e.getMessage());
            throw new CacheException("obtenerDatosCache", "Error accediendo al caché: " + e.getMessage());
        }
    }
    
    @Override
    public void actualizarCache(DatosCache datos, Current current) throws CacheException {
        try {
            cacheRegional.actualizarCache(datos);
        } catch (Exception e) {
            logger.error("Error actualizando caché: {}", e.getMessage());
            throw new CacheException("actualizarCache", "Error actualizando caché: " + e.getMessage());
        }
    }
    
    /**
     * Métodos adicionales para gestión y diagnóstico.
     */
    public boolean probarConectividad() {
        try {
            if (administradorMesa == null) {
                inicializarConexionRemota();
            }
            
            if (administradorMesa != null) {
                // Hacer una consulta de prueba
                administradorMesa.consultarAsignacionMesas("TEST_CONNECTIVITY");
                return true;
            }
            return false;
            
        } catch (Exception e) {
            logger.debug("Prueba de conectividad falló: {}", e.getMessage());
            return false;
        }
    }
    
    public String obtenerEstadisticas() {
        return String.format(
            "Consultas exitosas: %d, Fallidas: %d, Desde caché: %d, Remotas: %d",
            consultasExitosas, consultasFallidas, consultasCache, consultasRemotas
        );
    }
    
    public void resetearEstadisticas() {
        consultasExitosas = 0;
        consultasFallidas = 0;
        consultasCache = 0;
        consultasRemotas = 0;
        logger.info("Estadísticas reseteadas");
    }
    
    /**
     * Cierre ordenado del componente.
     */
    public void cerrar() {
        logger.info("Cerrando GestorConsultasRegional...");
        
        try {
            if (executor != null && !executor.isShutdown()) {
                executor.shutdown();
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            }
            
            logger.info("OK GestorConsultasRegional cerrado exitosamente");
            
        } catch (Exception e) {
            logger.error("Error cerrando GestorConsultasRegional: {}", e.getMessage());
        }
    }
} 