package com.registraduria.votacion.web.subscriber;

import Votacion.*;
import com.zeroc.Ice.Current;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SubscriberManagerImpl - Implementación del gestor de suscripciones.
 * 
 * Características:
 * - Gestión de suscripciones de clientes a resultados
 * - Distribución de actualizaciones en tiempo real
 * - Control de estado de suscripciones (ACTIVA, PAUSADA, CANCELADA, EXPIRADA)
 * - Filtros de resultados por región/candidato/partido/mesa
 * - Limpieza automática de suscripciones expiradas
 * - Métricas de rendimiento
 * - Thread-safe para alta concurrencia
 * - Soporte para 10,000+ clientes concurrentes
 */
public class SubscriberManagerImpl implements SubscriberManager {
    private static final Logger logger = LoggerFactory.getLogger(SubscriberManagerImpl.class);
    
    // Cache de resultados para almacenamiento temporal
    private final ResultadosCachePrx resultadosCache;
    
    // Control de suscripciones
    private final ConcurrentHashMap<String, SuscripcionData> suscripciones;
    private final ConcurrentHashMap<String, InfoCliente> clientes;
    private final ReadWriteLock subscribersLock;
    
    // Configuración
    private final int maxClientes;
    private static final int DURACION_SUSCRIPCION_DEFAULT = 3600; // 1 hora
    private static final int INTERVALO_ACTUALIZACION_DEFAULT = 5; // 5 segundos
    
    // Métricas
    private final AtomicLong suscripcionesCreadas = new AtomicLong(0);
    private final AtomicLong suscripcionesCanceladas = new AtomicLong(0);
    private final AtomicLong actualizacionesDistribuidas = new AtomicLong(0);
    private final AtomicLong erroresDistribucion = new AtomicLong(0);
    
    // Servicios auxiliares
    private final ScheduledExecutorService executorDistribucion;
    private final ScheduledExecutorService executorMantenimiento;
    
    /**
     * Datos internos de suscripción extendidos.
     */
    private static class SuscripcionData {
        SuscripcionResultados suscripcion;
        long timestampCreacion;
        long ultimaActualizacion;
        long proximaActualizacion;
        boolean activa;
        int fallosConsecutivos;
        
        SuscripcionData(SuscripcionResultados suscripcion) {
            this.suscripcion = suscripcion;
            this.timestampCreacion = System.currentTimeMillis();
            this.ultimaActualizacion = timestampCreacion;
            this.proximaActualizacion = timestampCreacion + (suscripcion.intervaloActualizacion * 1000L);
            this.activa = true;
            this.fallosConsecutivos = 0;
        }
        
        boolean estaExpirada() {
            // Expirar después de 1 hora sin actividad o 5 fallos consecutivos
            long tiempoInactivo = System.currentTimeMillis() - ultimaActualizacion;
            return tiempoInactivo > (DURACION_SUSCRIPCION_DEFAULT * 1000L) || fallosConsecutivos >= 5;
        }
        
        boolean esTiempoActualizacion() {
            return System.currentTimeMillis() >= proximaActualizacion;
        }
        
        void marcarActualizacion() {
            this.ultimaActualizacion = System.currentTimeMillis();
            this.proximaActualizacion = ultimaActualizacion + (suscripcion.intervaloActualizacion * 1000L);
            this.fallosConsecutivos = 0;
        }
        
        void marcarFallo() {
            this.fallosConsecutivos++;
        }
    }
    
    /**
     * Constructor del SubscriberManager.
     */
    public SubscriberManagerImpl(ResultadosCachePrx resultadosCache, int maxClientes) {
        this.resultadosCache = resultadosCache;
        this.maxClientes = maxClientes;
        
        this.suscripciones = new ConcurrentHashMap<>();
        this.clientes = new ConcurrentHashMap<>();
        this.subscribersLock = new ReentrantReadWriteLock();
        
        // Ejecutor para distribución de actualizaciones (más hilos para mayor concurrencia)
        this.executorDistribucion = Executors.newScheduledThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors())
        );
        
        // Ejecutor para mantenimiento
        this.executorMantenimiento = Executors.newScheduledThreadPool(2);
        
        configurarMantenimiento();
        
        logger.info("SubscriberManager inicializado - MaxClientes: {}", maxClientes);
    }
    
    /**
     * Configura tareas de mantenimiento automático.
     */
    private void configurarMantenimiento() {
        // Limpieza de suscripciones expiradas cada 30 segundos
        executorMantenimiento.scheduleAtFixedRate(
            this::limpiarSuscripcionesExpiradas,
            30, 30, TimeUnit.SECONDS
        );
        
        // Estadísticas periódicas cada 5 minutos
        executorMantenimiento.scheduleAtFixedRate(
            this::registrarEstadisticasPeriodicas,
            5, 5, TimeUnit.MINUTES
        );
        
        logger.info("Tareas de mantenimiento configuradas");
    }
    
    @Override
    public SuscripcionResultados suscribirseResultados(String clientId, FiltroResultados filtros, Current current) 
            throws ClienteNoExisteException, ErrorPersistenciaException {
        
        if (clientId == null || clientId.trim().isEmpty()) {
            throw new ClienteNoExisteException(clientId, "Cliente ID no puede estar vacío");
        }
        
        if (filtros == null) {
            throw new ErrorPersistenciaException("Filtros no pueden ser nulos");
        }
        
        clientId = clientId.trim();
        
        subscribersLock.writeLock().lock();
        try {
            // Verificar límite de clientes
            if (clientes.size() >= maxClientes && !clientes.containsKey(clientId)) {
                throw new ErrorPersistenciaException("Limite maximo de clientes alcanzado: " + maxClientes);
            }
            
            // Registrar o actualizar cliente
            InfoCliente cliente = clientes.get(clientId);
            if (cliente == null) {
                cliente = new InfoCliente();
                cliente.clientId = clientId;
                cliente.direccionIP = obtenerIPDesdeCurrentContext(current);
                cliente.agente = "ServidorWeb";
                cliente.fechaConexion = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                cliente.suscripcionesActivas = 0;
                cliente.estado = EstadoSuscripcion.ACTIVA;
                
                clientes.put(clientId, cliente);
                logger.info("Nuevo cliente registrado: {}", clientId);
            }
            
            // Crear suscripción
            SuscripcionResultados suscripcion = new SuscripcionResultados();
            suscripcion.suscripcionId = generarIdSuscripcion();
            suscripcion.clientId = clientId;
            suscripcion.region = filtros.region != null ? filtros.region : "TODAS";
            suscripcion.filtro = filtros.tipo;
            suscripcion.estado = EstadoSuscripcion.ACTIVA;
            suscripcion.fechaCreacion = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            suscripcion.intervaloActualizacion = INTERVALO_ACTUALIZACION_DEFAULT;
            
            // Registrar suscripción
            SuscripcionData suscripcionData = new SuscripcionData(suscripcion);
            suscripciones.put(suscripcion.suscripcionId, suscripcionData);
            
            // Actualizar cliente
            cliente.suscripcionesActivas++;
            
            suscripcionesCreadas.incrementAndGet();
            
            logger.info("Suscripción creada: {} para cliente: {}, región: {}", 
                       suscripcion.suscripcionId, clientId, suscripcion.region);
            
            return suscripcion;
            
        } catch (ErrorPersistenciaException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error creando suscripción para cliente {}: {}", clientId, e.getMessage());
            throw new ErrorPersistenciaException("Error creando suscripcion: " + e.getMessage());
        } finally {
            subscribersLock.writeLock().unlock();
        }
    }
    
    @Override
    public void cancelarSuscripcion(String suscripcionId, Current current) 
            throws SuscripcionInvalidaException, ErrorPersistenciaException {
        
        if (suscripcionId == null || suscripcionId.trim().isEmpty()) {
            throw new SuscripcionInvalidaException(suscripcionId, "ID de suscripción no puede estar vacío");
        }
        
        suscripcionId = suscripcionId.trim();
        
        subscribersLock.writeLock().lock();
        try {
            SuscripcionData suscripcionData = suscripciones.get(suscripcionId);
            
            if (suscripcionData == null) {
                throw new SuscripcionInvalidaException(suscripcionId, 
                    "Suscripción no encontrada: " + suscripcionId);
            }
            
            // Cancelar suscripción
            suscripcionData.suscripcion.estado = EstadoSuscripcion.CANCELADA;
            suscripcionData.activa = false;
            
            // Actualizar cliente
            String clientId = suscripcionData.suscripcion.clientId;
            InfoCliente cliente = clientes.get(clientId);
            if (cliente != null) {
                cliente.suscripcionesActivas = Math.max(0, cliente.suscripcionesActivas - 1);
                
                // Si no tiene más suscripciones, actualizar estado
                if (cliente.suscripcionesActivas == 0) {
                    cliente.estado = EstadoSuscripcion.CANCELADA;
                }
            }
            
            suscripcionesCanceladas.incrementAndGet();
            
            logger.info("Suscripción cancelada: {} de cliente: {}", suscripcionId, clientId);
            
        } catch (SuscripcionInvalidaException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Error cancelando suscripción {}: {}", suscripcionId, e.getMessage());
            throw new ErrorPersistenciaException("Error cancelando suscripcion: " + e.getMessage());
        } finally {
            subscribersLock.writeLock().unlock();
        }
    }
    
    @Override
    public void distribuirActualizacion(String resultados, String region, Current current) 
            throws ErrorPersistenciaException {
        
        if (resultados == null || region == null) {
            throw new ErrorPersistenciaException("Resultados y region no pueden ser nulos");
        }
        
        region = region.trim();
        
        try {
            logger.debug("Distribuyendo actualización para región: {}", region);
            
            // Obtener suscripciones relevantes
            var suscripcionesRelevantes = obtenerSuscripcionesParaRegion(region);
            
            if (suscripcionesRelevantes.isEmpty()) {
                logger.debug("No hay suscripciones activas para región: {}", region);
                return;
            }
            
            // Almacenar en cache temporal
            DatosCacheResultados datosCache = new DatosCacheResultados();
            datosCache.clave = "actualizacion_" + region + "_" + System.currentTimeMillis();
            datosCache.region = region;
            datosCache.datosResultados = resultados;
            datosCache.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            datosCache.ttl = 300; // 5 minutos
            datosCache.valido = true;
            
            if (resultadosCache != null) {
                resultadosCache.almacenarResultadosTemporal(region, datosCache, null);
            } else {
                throw new ErrorPersistenciaException("ResultadosCache no disponible");
            }
            
            // Distribuir a clientes suscritos de forma asíncrona
            for (SuscripcionData suscripcionData : suscripcionesRelevantes) {
                if (suscripcionData.esTiempoActualizacion()) {
                    executorDistribucion.submit(() -> 
                        enviarActualizacionACliente(suscripcionData, resultados));
                }
            }
            
            actualizacionesDistribuidas.incrementAndGet();
            
            logger.debug("Actualización distribuida a {} suscripciones para región: {}", 
                        suscripcionesRelevantes.size(), region);
            
        } catch (Exception e) {
            erroresDistribucion.incrementAndGet();
            logger.error("Error distribuyendo actualización para región {}: {}", region, e.getMessage());
            throw new ErrorPersistenciaException("Error distribuyendo actualizacion: " + e.getMessage());
        }
    }
    
    @Override
    public void almacenarResultadosTemporal(String region, DatosCacheResultados datos, Current current) 
            throws ErrorPersistenciaException {
        
        try {
            if (resultadosCache != null) {
                resultadosCache.almacenarResultadosTemporal(region, datos, null);
                logger.debug("Resultados almacenados temporalmente para región: {}", region);
            } else {
                throw new ErrorPersistenciaException("ResultadosCache no disponible");
            }
            
        } catch (Exception e) {
            logger.error("Error almacenando resultados temporales: {}", e.getMessage());
            throw new ErrorPersistenciaException("Error almacenando: " + e.getMessage());
        }
    }
    
    /**
     * Obtiene suscripciones relevantes para una región.
     */
    private java.util.List<SuscripcionData> obtenerSuscripcionesParaRegion(String region) {
        subscribersLock.readLock().lock();
        try {
            return suscripciones.values().stream()
                .filter(sub -> sub.activa)
                .filter(sub -> sub.suscripcion.estado == EstadoSuscripcion.ACTIVA)
                .filter(sub -> !sub.estaExpirada())
                .filter(sub -> "TODAS".equals(sub.suscripcion.region) || 
                             region.equals(sub.suscripcion.region))
                .collect(Collectors.toList());
        } finally {
            subscribersLock.readLock().unlock();
        }
    }
    
    /**
     * Envía actualización a un cliente específico.
     */
    private void enviarActualizacionACliente(SuscripcionData suscripcionData, String resultados) {
        try {
            // Aquí se implementaría el envío real al cliente
            // Por ahora simulamos el envío exitoso
            
            String suscripcionId = suscripcionData.suscripcion.suscripcionId;
            String clientId = suscripcionData.suscripcion.clientId;
            
            // Marcar actualización exitosa
            suscripcionData.marcarActualizacion();
            
            logger.debug("Actualización enviada exitosamente - Suscripción: {}, Cliente: {}", 
                        suscripcionId, clientId);
            
        } catch (Exception e) {
            suscripcionData.marcarFallo();
            logger.warn("Error enviando actualización a cliente {}: {}", 
                       suscripcionData.suscripcion.clientId, e.getMessage());
        }
    }
    
    /**
     * Limpia suscripciones expiradas.
     */
    public void limpiarSuscripcionesExpiradas() {
        subscribersLock.writeLock().lock();
        try {
            var suscripcionesExpiradas = suscripciones.entrySet().stream()
                .filter(entry -> entry.getValue().estaExpirada())
                .map(entry -> entry.getKey())
                .collect(Collectors.toList());
            
            for (String suscripcionId : suscripcionesExpiradas) {
                SuscripcionData suscripcionData = suscripciones.remove(suscripcionId);
                if (suscripcionData != null) {
                    suscripcionData.suscripcion.estado = EstadoSuscripcion.EXPIRADA;
                    
                    // Actualizar cliente
                    String clientId = suscripcionData.suscripcion.clientId;
                    InfoCliente cliente = clientes.get(clientId);
                    if (cliente != null) {
                        cliente.suscripcionesActivas = Math.max(0, cliente.suscripcionesActivas - 1);
                        if (cliente.suscripcionesActivas == 0) {
                            cliente.estado = EstadoSuscripcion.EXPIRADA;
                            // Opcional: remover cliente si no tiene suscripciones
                            clientes.remove(clientId);
                        }
                    }
                }
            }
            
            if (!suscripcionesExpiradas.isEmpty()) {
                logger.info("Limpiadas {} suscripciones expiradas", suscripcionesExpiradas.size());
            }
            
        } catch (Exception e) {
            logger.error("Error limpiando suscripciones expiradas: {}", e.getMessage());
        } finally {
            subscribersLock.writeLock().unlock();
        }
    }
    
    /**
     * Registra estadísticas periódicas.
     */
    private void registrarEstadisticasPeriodicas() {
        try {
            subscribersLock.readLock().lock();
            try {
                int suscripcionesActivas = (int) suscripciones.values().stream()
                    .filter(sub -> sub.activa && !sub.estaExpirada())
                    .count();
                
                logger.info("Estadísticas SubscriberManager - Clientes: {}, Suscripciones activas: {}, " +
                           "Creadas: {}, Canceladas: {}, Actualizaciones: {}, Errores: {}", 
                           clientes.size(), suscripcionesActivas, suscripcionesCreadas.get(),
                           suscripcionesCanceladas.get(), actualizacionesDistribuidas.get(),
                           erroresDistribucion.get());
            } finally {
                subscribersLock.readLock().unlock();
            }
        } catch (Exception e) {
            logger.error("Error registrando estadísticas: {}", e.getMessage());
        }
    }
    
    /**
     * Obtiene estadísticas de suscripciones.
     */
    public String obtenerEstadisticasSuscripciones() {
        subscribersLock.readLock().lock();
        try {
            int suscripcionesActivas = (int) suscripciones.values().stream()
                .filter(sub -> sub.activa && !sub.estaExpirada())
                .count();
            
            int clientesActivos = (int) clientes.values().stream()
                .filter(cliente -> cliente.suscripcionesActivas > 0)
                .count();
            
            return String.format(
                "SubscriberManager - Clientes activos: %d/%d | Suscripciones activas: %d | " +
                "Creadas: %d | Canceladas: %d | Actualizaciones distribuidas: %d | Errores: %d",
                clientesActivos, clientes.size(), suscripcionesActivas, 
                suscripcionesCreadas.get(), suscripcionesCanceladas.get(),
                actualizacionesDistribuidas.get(), erroresDistribucion.get()
            );
        } finally {
            subscribersLock.readLock().unlock();
        }
    }
    
    /**
     * Genera ID único para suscripción.
     */
    private String generarIdSuscripcion() {
        return "sub_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    /**
     * Obtiene IP del contexto actual (simulado).
     */
    private String obtenerIPDesdeCurrentContext(Current current) {
        // En una implementación real, se obtendría del contexto ICE
        return "127.0.0.1";
    }
    
    /**
     * Cierre ordenado del SubscriberManager.
     */
    public void cerrar() {
        logger.info("Cerrando SubscriberManager...");
        
        try {
            // Detener ejecutores
            if (executorDistribucion != null && !executorDistribucion.isShutdown()) {
                executorDistribucion.shutdown();
                if (!executorDistribucion.awaitTermination(10, TimeUnit.SECONDS)) {
                    executorDistribucion.shutdownNow();
                }
            }
            
            if (executorMantenimiento != null && !executorMantenimiento.isShutdown()) {
                executorMantenimiento.shutdown();
                if (!executorMantenimiento.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorMantenimiento.shutdownNow();
                }
            }
            
            // Cancelar todas las suscripciones activas
            subscribersLock.writeLock().lock();
            try {
                for (SuscripcionData suscripcionData : suscripciones.values()) {
                    if (suscripcionData.activa) {
                        suscripcionData.suscripcion.estado = EstadoSuscripcion.CANCELADA;
                        suscripcionData.activa = false;
                    }
                }
                
                logger.info("Todas las suscripciones han sido canceladas");
            } finally {
                subscribersLock.writeLock().unlock();
            }
            
            logger.info("OK SubscriberManager cerrado exitosamente");
            logger.info("Estadísticas finales: {}", obtenerEstadisticasSuscripciones());
            
        } catch (Exception e) {
            logger.error("Error cerrando SubscriberManager: {}", e.getMessage());
        }
    }
} 