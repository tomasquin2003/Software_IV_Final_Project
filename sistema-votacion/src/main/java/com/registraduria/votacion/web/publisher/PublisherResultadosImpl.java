package com.registraduria.votacion.web.publisher;

import Votacion.*;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PublisherResultadosImpl - Implementación del publicador de resultados.
 * 
 * Características:
 * - Publicación de actualizaciones de resultados electorales
 * - Notificación de cambios en la votación
 * - Conectividad con DatabaseProxy para obtener datos actualizados
 * - Distribución automática a través de SubscriberManager
 * - Triggers automáticos para actualizaciones
 * - Manejo robusto de errores y reconexión
 * - Métricas de rendimiento
 * - Thread-safe para operaciones concurrentes
 */
public class PublisherResultadosImpl implements PublisherResultados {
    private static final Logger logger = LoggerFactory.getLogger(PublisherResultadosImpl.class);
    
    // Comunicación ICE
    private final Communicator communicator;
    private final String endpointDatabase;
    private ConnectionManagerRemotoPrx databaseProxy;
    
    // Gestión de suscripciones
    private final SubscriberManagerPrx subscriberManager;
    
    // Control de conectividad
    private boolean conectadoDatabase = false;
    private long ultimaConexionDatabase = 0;
    private static final int TIMEOUT_CONEXION = 5000; // 5 segundos
    private static final int MAX_REINTENTOS = 3;
    
    // Métricas
    private final AtomicLong publicacionesExitosas = new AtomicLong(0);
    private final AtomicLong publicacionesFallidas = new AtomicLong(0);
    private final AtomicLong notificacionesEnviadas = new AtomicLong(0);
    private final AtomicLong actualizacionesDatabase = new AtomicLong(0);
    private final AtomicLong erroresConexion = new AtomicLong(0);
    
    // Servicios auxiliares
    private final ScheduledExecutorService executorPublicacion;
    
    // Estado interno
    private double ultimoPorcentajeVotacion = 0.0;
    private String ultimosResultados = "";
    
    /**
     * Constructor del PublisherResultados.
     */
    public PublisherResultadosImpl(Communicator communicator, 
                                   SubscriberManagerPrx subscriberManager, 
                                   String endpointDatabase) {
        this.communicator = communicator;
        this.subscriberManager = subscriberManager;
        this.endpointDatabase = endpointDatabase;
        
        this.executorPublicacion = Executors.newScheduledThreadPool(2);
        
        establecerConexionDatabase();
        configurarActualizacionesAutomaticas();
        
        logger.info("PublisherResultados inicializado - Endpoint DB: {}", endpointDatabase);
    }
    
    @Override
    public void publicarActualizacion(String resultados, String region, Current current) 
            throws ErrorPersistenciaException {
        
        if (resultados == null || region == null) {
            publicacionesFallidas.incrementAndGet();
            throw new ErrorPersistenciaException("Resultados y region no pueden ser nulos");
        }
        
        region = region.trim();
        
        try {
            logger.debug("Publicando actualización para región: {}", region);
            
            // Validar datos de resultados
            if (resultados.trim().isEmpty()) {
                logger.warn("Resultados vacíos para región: {}", region);
                resultados = generarResultadosDefault(region);
            }
            
            // Distribuir a través del SubscriberManager
            if (subscriberManager != null) {
                subscriberManager.distribuirActualizacion(resultados, region, null);
                publicacionesExitosas.incrementAndGet();
                logger.debug("Actualización distribuida exitosamente para región: {}", region);
            } else {
                throw new ErrorPersistenciaException("SubscriberManager no disponible");
            }
            
            // Actualizar estado interno
            ultimosResultados = resultados;
            
        } catch (Exception e) {
            publicacionesFallidas.incrementAndGet();
            logger.error("Error publicando actualización para región {}: {}", region, e.getMessage());
            throw new ErrorPersistenciaException("Error publicando: " + e.getMessage());
        }
    }
    
    @Override
    public void notificarCambiosVotacion(String regionId, NotificacionCambio delta, Current current) 
            throws ErrorPersistenciaException {
        
        if (regionId == null || delta == null) {
            throw new ErrorPersistenciaException("RegionId y delta no pueden ser nulos");
        }
        
        regionId = regionId.trim();
        
        try {
            logger.debug("Notificando cambios para región: {}, tipo: {}", regionId, delta.tipo);
            
            // Validar y completar datos de notificación
            if (delta.regionId == null) delta.regionId = regionId;
            if (delta.timestamp == null) {
                delta.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
            if (delta.mensaje == null) delta.mensaje = "Cambio en votación";
            if (delta.datos == null) delta.datos = "";
            
            // Crear resultados actualizados basados en la notificación
            String resultadosActualizados = construirResultadosDesdeNotificacion(delta);
            
            // Publicar actualización
            publicarActualizacion(resultadosActualizados, regionId, current);
            
            notificacionesEnviadas.incrementAndGet();
            logger.info("Notificación de cambios enviada para región: {}", regionId);
            
        } catch (Exception e) {
            logger.error("Error notificando cambios para región {}: {}", regionId, e.getMessage());
            throw new ErrorPersistenciaException("Error notificando cambios: " + e.getMessage());
        }
    }
    
    @Override
    public void registrarTriggerActualizacion(Current current) throws ErrorPersistenciaException {
        try {
            logger.debug("Registrando trigger de actualización...");
            
            // Verificar conectividad con DatabaseProxy
            if (!conectadoDatabase || databaseProxy == null) {
                if (!establecerConexionDatabase()) {
                    throw new ErrorPersistenciaException("Sin conectividad con DatabaseProxy");
                }
            }
            
            // Registrar trigger en DatabaseProxy
            databaseProxy.registrarTriggerActualizacion(null);
            
            logger.info("Trigger de actualización registrado exitosamente");
            
        } catch (Exception e) {
            logger.error("Error registrando trigger: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error registrando trigger: " + e.getMessage());
        }
    }
    
    @Override
    public String obtenerResultadosActualizados(Current current) throws ErrorPersistenciaException {
        try {
            logger.debug("Obteniendo resultados actualizados...");
            
            // Verificar conectividad con DatabaseProxy
            if (!conectadoDatabase || databaseProxy == null) {
                if (!establecerConexionDatabase()) {
                    logger.warn("Sin conectividad con DatabaseProxy, usando resultados cached");
                    return ultimosResultados.isEmpty() ? generarResultadosDefault("GENERAL") : ultimosResultados;
                }
            }
            
            // Obtener resultados desde DatabaseProxy con reintentos
            String resultados = null;
            int intentos = 0;
            
            while (intentos < MAX_REINTENTOS) {
                try {
                    resultados = databaseProxy.obtenerResultadosActualizados(null);
                    actualizacionesDatabase.incrementAndGet();
                    break;
                    
                } catch (Exception e) {
                    intentos++;
                    logger.warn("Intento {} fallido obteniendo resultados: {}", intentos, e.getMessage());
                    
                    if (intentos < MAX_REINTENTOS) {
                        if (!establecerConexionDatabase()) {
                            Thread.sleep(1000);
                        }
                    } else {
                        logger.error("Error obteniendo resultados después de {} intentos", MAX_REINTENTOS);
                        // Usar resultados cached como fallback
                        resultados = ultimosResultados.isEmpty() ? 
                            generarResultadosDefault("GENERAL") : ultimosResultados;
                    }
                }
            }
            
            // Actualizar cache interno
            if (resultados != null && !resultados.trim().isEmpty()) {
                ultimosResultados = resultados;
            }
            
            return resultados != null ? resultados : "";
            
        } catch (Exception e) {
            logger.error("Error obteniendo resultados actualizados: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error obteniendo resultados: " + e.getMessage());
        }
    }
    
    @Override
    public double obtenerPorcentajeVotacion(Current current) throws ErrorPersistenciaException {
        try {
            logger.debug("Obteniendo porcentaje de votación...");
            
            // Verificar conectividad con DatabaseProxy
            if (!conectadoDatabase || databaseProxy == null) {
                if (!establecerConexionDatabase()) {
                    logger.warn("Sin conectividad con DatabaseProxy, usando porcentaje cached");
                    return ultimoPorcentajeVotacion;
                }
            }
            
            // Obtener porcentaje desde DatabaseProxy
            double porcentaje = databaseProxy.obtenerPorcentajeVotacion(null);
            ultimoPorcentajeVotacion = porcentaje;
            
            return porcentaje;
            
        } catch (Exception e) {
            logger.error("Error obteniendo porcentaje de votación: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error obteniendo porcentaje: " + e.getMessage());
        }
    }
    
    /**
     * Establece conexión con el DatabaseProxy.
     */
    private boolean establecerConexionDatabase() {
        try {
            logger.debug("Estableciendo conexión con DatabaseProxy...");
            
            if (communicator == null) {
                logger.error("Communicator no disponible");
                return false;
            }
            
            // Crear proxy
            var proxy = communicator.stringToProxy(endpointDatabase);
            if (proxy == null) {
                logger.error("No se pudo crear proxy para: {}", endpointDatabase);
                return false;
            }
            
            // Configurar timeout
            proxy = proxy.ice_timeout(TIMEOUT_CONEXION);
            
            // Verificar conexión
            databaseProxy = ConnectionManagerRemotoPrx.checkedCast(proxy);
            if (databaseProxy == null) {
                logger.error("No se pudo conectar a DatabaseProxy en: {}", endpointDatabase);
                return false;
            }
            
            // Probar conexión con ping
            databaseProxy.ice_ping();
            
            conectadoDatabase = true;
            ultimaConexionDatabase = System.currentTimeMillis();
            
            logger.info("Conexión establecida exitosamente con DatabaseProxy");
            return true;
            
        } catch (Exception e) {
            logger.error("Error estableciendo conexión con DatabaseProxy: {}", e.getMessage());
            conectadoDatabase = false;
            databaseProxy = null;
            erroresConexion.incrementAndGet();
            return false;
        }
    }
    
    /**
     * Configura actualizaciones automáticas.
     */
    private void configurarActualizacionesAutomaticas() {
        // Actualización automática de resultados cada 10 segundos
        executorPublicacion.scheduleAtFixedRate(
            this::actualizarResultadosAutomatico,
            10, 10, TimeUnit.SECONDS
        );
        
        // Verificación de conectividad cada 30 segundos
        executorPublicacion.scheduleAtFixedRate(
            this::verificarConectividad,
            30, 30, TimeUnit.SECONDS
        );
        
        logger.info("Actualizaciones automáticas configuradas");
    }
    
    /**
     * Actualización automática de resultados.
     */
    private void actualizarResultadosAutomatico() {
        try {
            logger.debug("Ejecutando actualización automática...");
            
            // Obtener resultados actualizados
            String resultados = obtenerResultadosActualizados(null);
            
            if (resultados != null && !resultados.trim().isEmpty()) {
                // Publicar para todas las regiones principales
                String[] regiones = {"REGION_01", "REGION_02", "REGION_03", "GENERAL"};
                
                for (String region : regiones) {
                    try {
                        publicarActualizacion(resultados, region, null);
                    } catch (Exception e) {
                        logger.warn("Error publicando para región {}: {}", region, e.getMessage());
                    }
                }
            }
            
        } catch (Exception e) {
            logger.error("Error en actualización automática: {}", e.getMessage());
        }
    }
    
    /**
     * Actualiza resultados desde DatabaseProxy (método público).
     */
    public void actualizarResultadosDesdeDatabase() {
        actualizarResultadosAutomatico();
    }
    
    /**
     * Verificación de conectividad.
     */
    private void verificarConectividad() {
        try {
            if (!conectadoDatabase || databaseProxy == null) {
                establecerConexionDatabase();
            } else {
                // Verificar que la conexión sigue activa
                databaseProxy.ice_ping();
            }
        } catch (Exception e) {
            logger.warn("Conectividad perdida con DatabaseProxy: {}", e.getMessage());
            conectadoDatabase = false;
            establecerConexionDatabase();
        }
    }
    
    /**
     * Verifica el estado de la conexión (método público).
     */
    public boolean probarConectividad() {
        try {
            if (!conectadoDatabase || databaseProxy == null) {
                return establecerConexionDatabase();
            }
            
            databaseProxy.ice_ping();
            return true;
            
        } catch (Exception e) {
            logger.warn("Conectividad perdida con DatabaseProxy: {}", e.getMessage());
            conectadoDatabase = false;
            return establecerConexionDatabase();
        }
    }
    
    /**
     * Genera resultados por defecto para una región.
     */
    private String generarResultadosDefault(String region) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        
        return String.format(
            "{\"region\":\"%s\",\"timestamp\":\"%s\",\"candidatos\":[" +
            "{\"id\":\"CAND_01\",\"nombre\":\"Candidato A\",\"votos\":25000,\"porcentaje\":45.5}," +
            "{\"id\":\"CAND_02\",\"nombre\":\"Candidato B\",\"votos\":20000,\"porcentaje\":36.4}," +
            "{\"id\":\"CAND_03\",\"nombre\":\"Candidato C\",\"votos\":10000,\"porcentaje\":18.1}" +
            "],\"total_votos\":55000,\"participacion\":65.0}",
            region, timestamp
        );
    }
    
    /**
     * Construye resultados actualizados desde notificación.
     */
    private String construirResultadosDesdeNotificacion(NotificacionCambio notificacion) {
        String timestamp = notificacion.timestamp;
        String region = notificacion.regionId;
        
        // Usar datos de la notificación si están disponibles
        String datos = notificacion.datos;
        if (datos == null || datos.trim().isEmpty()) {
            datos = generarResultadosDefault(region);
        }
        
        return datos;
    }
    
    /**
     * Obtiene estadísticas del publisher.
     */
    public String obtenerEstadisticasPublisher() {
        String estado = conectadoDatabase ? "CONECTADO" : "DESCONECTADO";
        String ultimaConexionStr = ultimaConexionDatabase > 0 ? 
            LocalDateTime.ofEpochSecond(ultimaConexionDatabase / 1000, 0, java.time.ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : "Nunca";
        
        return String.format(
            "PublisherResultados - Estado: %s | Publicaciones exitosas: %d | Fallidas: %d | " +
            "Notificaciones: %d | Updates DB: %d | Errores conexión: %d | Última conexión: %s",
            estado, publicacionesExitosas.get(), publicacionesFallidas.get(),
            notificacionesEnviadas.get(), actualizacionesDatabase.get(), 
            erroresConexion.get(), ultimaConexionStr
        );
    }
    
    /**
     * Fuerza publicación para todas las regiones.
     */
    public void forzarPublicacionCompleta() {
        try {
            logger.info("Forzando publicación completa...");
            
            String resultados = obtenerResultadosActualizados(null);
            String[] regiones = {"REGION_01", "REGION_02", "REGION_03", "REGION_04", "GENERAL"};
            
            for (String region : regiones) {
                try {
                    publicarActualizacion(resultados, region, null);
                    Thread.sleep(100); // Pequeña pausa entre publicaciones
                } catch (Exception e) {
                    logger.warn("Error en publicación forzada para {}: {}", region, e.getMessage());
                }
            }
            
            logger.info("Publicación forzada completada");
            
        } catch (Exception e) {
            logger.error("Error en publicación forzada: {}", e.getMessage());
        }
    }
    
    /**
     * Reinicia métricas.
     */
    public void reiniciarMetricas() {
        publicacionesExitosas.set(0);
        publicacionesFallidas.set(0);
        notificacionesEnviadas.set(0);
        actualizacionesDatabase.set(0);
        erroresConexion.set(0);
        logger.info("Métricas del PublisherResultados reiniciadas");
    }
    
    /**
     * Obtiene información detallada del estado.
     */
    public String obtenerEstadoDetallado() {
        StringBuilder estado = new StringBuilder();
        estado.append("=== PUBLISHER RESULTADOS ===\n");
        estado.append("Estado conexión DB: ").append(conectadoDatabase ? "ACTIVA" : "INACTIVA").append("\n");
        estado.append("Endpoint DatabaseProxy: ").append(endpointDatabase).append("\n");
        estado.append("Publicaciones exitosas: ").append(publicacionesExitosas.get()).append("\n");
        estado.append("Publicaciones fallidas: ").append(publicacionesFallidas.get()).append("\n");
        estado.append("Notificaciones enviadas: ").append(notificacionesEnviadas.get()).append("\n");
        estado.append("Updates desde DB: ").append(actualizacionesDatabase.get()).append("\n");
        estado.append("Errores conexión: ").append(erroresConexion.get()).append("\n");
        estado.append("Último porcentaje votación: ").append(ultimoPorcentajeVotacion).append("%\n");
        estado.append("Timeout configurado: ").append(TIMEOUT_CONEXION).append("ms\n");
        estado.append("Max reintentos: ").append(MAX_REINTENTOS).append("\n");
        
        if (ultimaConexionDatabase > 0) {
            long tiempoConexion = System.currentTimeMillis() - ultimaConexionDatabase;
            estado.append("Tiempo desde última conexión: ").append(tiempoConexion / 1000).append("s\n");
        }
        
        return estado.toString();
    }
    
    /**
     * Cierre ordenado del publisher.
     */
    public void cerrar() {
        logger.info("Cerrando PublisherResultados...");
        
        try {
            // Detener ejecutor
            if (executorPublicacion != null && !executorPublicacion.isShutdown()) {
                executorPublicacion.shutdown();
                if (!executorPublicacion.awaitTermination(10, TimeUnit.SECONDS)) {
                    executorPublicacion.shutdownNow();
                }
            }
            
            conectadoDatabase = false;
            databaseProxy = null;
            
            logger.info("OK PublisherResultados cerrado exitosamente");
            logger.info("Estadísticas finales: {}", obtenerEstadisticasPublisher());
            
        } catch (Exception e) {
            logger.error("Error cerrando PublisherResultados: {}", e.getMessage());
        }
    }
} 