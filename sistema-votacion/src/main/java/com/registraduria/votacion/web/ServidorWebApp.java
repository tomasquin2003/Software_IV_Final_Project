package com.registraduria.votacion.web;

import Votacion.*;
import com.registraduria.votacion.web.portal.PortalConsultasImpl;
import com.registraduria.votacion.web.visualizador.VisualizadorResultadosImpl;
import com.registraduria.votacion.web.publisher.PublisherResultadosImpl;
import com.registraduria.votacion.web.subscriber.SubscriberManagerImpl;
import com.registraduria.votacion.web.cache.ResultadosCacheImpl;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * ServidorWebApp - Aplicación principal del ServidorWeb.
 * 
 * Funcionalidades:
 * - Portal de consultas públicas de lugar de votación
 * - Visualización dinámica de resultados electorales
 * - Publisher de actualizaciones de resultados
 * - Gestión de suscripciones de clientes
 * - Cache de resultados para alto rendimiento
 * - Actualizaciones en tiempo real (patrón Publisher-Subscriber)
 * - Menú administrativo interactivo
 * - Monitoreo y métricas
 */
public class ServidorWebApp {
    private static final Logger logger = LoggerFactory.getLogger(ServidorWebApp.class);
    
    // Configuración
    private static final String CONFIG_FILE = "src/main/resources/config/web.config";
    private static final String DATA_DIR = "data/web";
    private static final String PUERTO_ICE = "10006"; // Nuevo puerto para ServidorWeb
    
    // Componentes ICE
    private Communicator communicator;
    private ObjectAdapter adapter;
    
    // Implementaciones
    private PortalConsultasImpl portalConsultas;
    private VisualizadorResultadosImpl visualizadorResultados;
    private PublisherResultadosImpl publisherResultados;
    private SubscriberManagerImpl subscriberManager;
    private ResultadosCacheImpl resultadosCache;
    
    // Servicios auxiliares
    private ScheduledExecutorService executorMantenimiento;
    
    // Estado
    private boolean servidorActivo = false;
    private Properties configuracion;
    private String endpointRegional;
    private String endpointDatabase;
    
    /**
     * Punto de entrada principal.
     */
    public static void main(String[] args) {
        ServidorWebApp servidor = new ServidorWebApp();
        
        try {
            servidor.inicializar();
            servidor.ejecutarMenuInteractivo();
            
        } catch (Exception e) {
            logger.error("Error crítico en ServidorWeb: {}", e.getMessage(), e);
            System.exit(1);
        } finally {
            servidor.cerrarServidor();
        }
    }
    
    /**
     * Inicializa el servidor web completo.
     */
    private void inicializar() throws Exception {
        logger.info("==== INICIANDO SERVIDOR WEB ====");
        
        // Cargar configuración
        cargarConfiguracion();
        
        // Crear directorio de datos
        crearDirectoriosDatos();
        
        // Inicializar ICE
        inicializarICE();
        
        // Crear componentes
        crearComponentes();
        
        // Configurar servicios
        configurarServicios();
        
        // Activar servidor
        activarServidor();
        
        logger.info("OK ServidorWeb iniciado exitosamente en puerto {}", PUERTO_ICE);
        logger.info("Endpoints disponibles:");
        logger.info("- PortalConsultas: PortalConsultas:tcp -p {}", PUERTO_ICE);
        logger.info("- VisualizadorResultados: VisualizadorResultados:tcp -p {}", PUERTO_ICE);
        logger.info("- PublisherResultados: PublisherResultados:tcp -p {}", PUERTO_ICE);
        logger.info("- SubscriberManager: SubscriberManager:tcp -p {}", PUERTO_ICE);
        logger.info("- ResultadosCache: ResultadosCache:tcp -p {}", PUERTO_ICE);
    }
    
    /**
     * Carga configuración desde archivo.
     */
    private void cargarConfiguracion() throws IOException {
        logger.info("Cargando configuración...");
        
        configuracion = new Properties();
        
        if (Files.exists(Paths.get(CONFIG_FILE))) {
            configuracion.load(Files.newInputStream(Paths.get(CONFIG_FILE)));
            logger.info("Configuración cargada desde: {}", CONFIG_FILE);
        } else {
            // Configuración por defecto
            configuracion.setProperty("web.puerto", PUERTO_ICE);
            configuracion.setProperty("web.maxClientes", "10000");
            configuracion.setProperty("cache.maxSize", "50000");
            configuracion.setProperty("cache.ttl", "300");
            configuracion.setProperty("endpoint.regional", "GestorConsultasRegional:tcp -h localhost -p 10005");
            configuracion.setProperty("endpoint.database", "ConnectionManagerRemoto:tcp -h localhost -p 10002");
            
            logger.warn("Archivo de configuración no encontrado, usando valores por defecto");
        }
        
        // Configurar endpoints
        endpointRegional = configuracion.getProperty("endpoint.regional");
        endpointDatabase = configuracion.getProperty("endpoint.database");
        
        logger.info("Configuración cargada - Max clientes: {}, Cache size: {}", 
                   configuracion.getProperty("web.maxClientes"),
                   configuracion.getProperty("cache.maxSize"));
    }
    
    /**
     * Crea directorios necesarios.
     */
    private void crearDirectoriosDatos() throws IOException {
        logger.info("Creando directorios de datos...");
        
        Files.createDirectories(Paths.get(DATA_DIR));
        Files.createDirectories(Paths.get(DATA_DIR + "/cache"));
        Files.createDirectories(Paths.get(DATA_DIR + "/logs"));
        
        logger.info("Directorios creados en: {}", DATA_DIR);
    }
    
    /**
     * Inicializa comunicador ICE.
     */
    private void inicializarICE() {
        logger.info("Inicializando ICE...");
        
        String[] iceArgs = {
            "--Ice.Config=" + CONFIG_FILE
        };
        
        communicator = Util.initialize(iceArgs);
        
        // Crear adaptador de objetos
        adapter = communicator.createObjectAdapterWithEndpoints(
            "ServidorWebAdapter", 
            "tcp -p " + PUERTO_ICE
        );
        
        logger.info("ICE inicializado en puerto: {}", PUERTO_ICE);
    }
    
    /**
     * Crea todos los componentes del servidor.
     */
    private void crearComponentes() throws Exception {
        logger.info("Creando componentes...");
        
        // Parámetros de configuración
        int maxClientes = Integer.parseInt(configuracion.getProperty("web.maxClientes", "10000"));
        int maxCacheSize = Integer.parseInt(configuracion.getProperty("cache.maxSize", "50000"));
        int cacheTTL = Integer.parseInt(configuracion.getProperty("cache.ttl", "300"));
        
        // 1. ResultadosCache (base para otros componentes)
        resultadosCache = new ResultadosCacheImpl(DATA_DIR, maxCacheSize, cacheTTL);
        
        // 2. SubscriberManager
        subscriberManager = new SubscriberManagerImpl(
            ResultadosCachePrx.uncheckedCast(
                adapter.add(resultadosCache, 
                    communicator.stringToIdentity("ResultadosCache"))
            ),
            maxClientes
        );
        
        // 3. PublisherResultados
        publisherResultados = new PublisherResultadosImpl(
            communicator,
            SubscriberManagerPrx.uncheckedCast(
                adapter.add(subscriberManager, 
                    communicator.stringToIdentity("SubscriberManager"))
            ),
            endpointDatabase
        );
        
        // 4. VisualizadorResultados
        visualizadorResultados = new VisualizadorResultadosImpl(
            SubscriberManagerPrx.uncheckedCast(
                communicator.stringToProxy("SubscriberManager:tcp -h localhost -p " + PUERTO_ICE)
            ),
            ResultadosCachePrx.uncheckedCast(
                communicator.stringToProxy("ResultadosCache:tcp -h localhost -p " + PUERTO_ICE)
            )
        );
        
        // 5. PortalConsultas
        portalConsultas = new PortalConsultasImpl(communicator, endpointRegional);
        
        logger.info("Componentes creados exitosamente");
    }
    
    /**
     * Configura servicios auxiliares.
     */
    private void configurarServicios() {
        logger.info("Configurando servicios auxiliares...");
        
        // Ejecutor para mantenimiento general
        executorMantenimiento = Executors.newScheduledThreadPool(2);
        
        // Estadísticas periódicas cada 2 minutos
        executorMantenimiento.scheduleAtFixedRate(
            this::registrarEstadisticasGenerales,
            2, 2, TimeUnit.MINUTES
        );
        
        // Monitoreo de conectividad cada 30 segundos
        executorMantenimiento.scheduleAtFixedRate(
            this::verificarConectividadServicios,
            30, 30, TimeUnit.SECONDS
        );
        
        logger.info("Servicios auxiliares configurados");
    }
    
    /**
     * Activa el servidor registrando todos los objetos.
     */
    private void activarServidor() {
        logger.info("Activando servidor...");
        
        // Registrar objetos en el adaptador
        adapter.add(portalConsultas, communicator.stringToIdentity("PortalConsultas"));
        adapter.add(visualizadorResultados, communicator.stringToIdentity("VisualizadorResultados"));
        adapter.add(publisherResultados, communicator.stringToIdentity("PublisherResultados"));
        
        // Activar adaptador
        adapter.activate();
        
        servidorActivo = true;
        logger.info("Servidor activado y listo para recibir conexiones");
    }
    
    /**
     * Ejecuta menú interactivo de administración.
     */
    private void ejecutarMenuInteractivo() {
        Scanner scanner = new Scanner(System.in);
        boolean continuar = true;
        
        while (continuar && servidorActivo) {
            mostrarMenu();
            
            try {
                String opcion = scanner.nextLine().trim();
                continuar = procesarOpcionMenu(opcion);
                
            } catch (Exception e) {
                logger.error("Error procesando opción del menú: {}", e.getMessage());
                System.out.println("ERROR: " + e.getMessage());
            }
            
            System.out.println(); // Línea en blanco
        }
        
        scanner.close();
    }
    
    /**
     * Muestra el menú principal.
     */
    private void mostrarMenu() {
        System.out.println("\n=================== SERVIDOR WEB ===================");
        System.out.println("Estado: " + (servidorActivo ? "ACTIVO" : "INACTIVO"));
        System.out.println("Puerto: " + PUERTO_ICE);
        System.out.println("====================================================");
        System.out.println("1. Ver estadísticas generales");
        System.out.println("2. Consultar lugar de votación");
        System.out.println("3. Ver resultados por región");
        System.out.println("4. Gestionar suscripciones");
        System.out.println("5. Publicar actualización");
        System.out.println("6. Limpiar cache de resultados");
        System.out.println("7. Verificar conectividad");
        System.out.println("8. Configuración avanzada");
        System.out.println("9. Optimizar sistema");
        System.out.println("0. Salir");
        System.out.print("Seleccione una opción: ");
    }
    
    /**
     * Procesa la opción seleccionada del menú.
     */
    private boolean procesarOpcionMenu(String opcion) throws Exception {
        switch (opcion) {
            case "1":
                mostrarEstadisticasGenerales();
                break;
                
            case "2":
                consultarLugarVotacion();
                break;
                
            case "3":
                verResultadosPorRegion();
                break;
                
            case "4":
                gestionarSuscripciones();
                break;
                
            case "5":
                publicarActualizacion();
                break;
                
            case "6":
                limpiarCacheResultados();
                break;
                
            case "7":
                verificarConectividad();
                break;
                
            case "8":
                configuracionAvanzada();
                break;
                
            case "9":
                optimizarSistema();
                break;
                
            case "0":
                System.out.println("Cerrando ServidorWeb...");
                return false;
                
            default:
                System.out.println("Opción no válida. Intente nuevamente.");
                break;
        }
        
        return true;
    }
    
    /**
     * Muestra estadísticas generales del sistema.
     */
    private void mostrarEstadisticasGenerales() {
        System.out.println("\n===== ESTADISTICAS GENERALES =====");
        
        try {
            if (resultadosCache != null) {
                System.out.println("Cache: " + resultadosCache.obtenerEstadisticasDetalladas());
            }
            
            if (subscriberManager != null) {
                System.out.println("Suscripciones: " + subscriberManager.obtenerEstadisticasSuscripciones());
            }
            
            if (publisherResultados != null) {
                System.out.println("Publisher: " + publisherResultados.obtenerEstadisticasPublisher());
            }
            
            if (visualizadorResultados != null) {
                System.out.println("Visualizador: " + visualizadorResultados.obtenerEstadisticasVisualizador());
            }
            
            if (portalConsultas != null) {
                System.out.println("Portal: " + portalConsultas.obtenerEstadisticasConsultas());
            }
            
        } catch (Exception e) {
            System.out.println("ERROR obteniendo estadísticas: " + e.getMessage());
        }
    }
    
    /**
     * Permite consultar lugar de votación.
     */
    private void consultarLugarVotacion() {
        Scanner scanner = new Scanner(System.in);
        
        try {
            System.out.print("Ingrese número de cédula: ");
            String cedula = scanner.nextLine().trim();
            
            if (cedula.isEmpty()) {
                System.out.println("Cédula no puede estar vacía");
                return;
            }
            
            LugarVotacion lugar = portalConsultas.consultarLugarVotacion(cedula, null);
            
            System.out.println("\n===== LUGAR DE VOTACION =====");
            System.out.println("Mesa ID: " + lugar.mesaId);
            System.out.println("Direccion: " + lugar.direccion);
            System.out.println("Horario apertura: " + lugar.horarioApertura);
            System.out.println("Horario cierre: " + lugar.horarioCierre);
            System.out.println("Responsable: " + lugar.responsable);
            System.out.println("Telefono: " + lugar.telefono);
            System.out.println("Estado: " + (lugar.activa ? "ACTIVA" : "INACTIVA"));
            
        } catch (Exception e) {
            System.out.println("ERROR consultando lugar: " + e.getMessage());
        }
    }
    
    /**
     * Permite ver resultados por región.
     */
    private void verResultadosPorRegion() {
        Scanner scanner = new Scanner(System.in);
        
        try {
            System.out.print("Ingrese región (REGION_01, REGION_02, etc. o 'TODAS'): ");
            String region = scanner.nextLine().trim().toUpperCase();
            
            if (region.isEmpty()) {
                region = "TODAS";
            }
            
            ResultadosRegion resultados = visualizadorResultados.obtenerResultadosRegion(region, null);
            
            System.out.println("\n===== RESULTADOS - " + resultados.nombreRegion + " =====");
            System.out.println("Total votantes: " + resultados.totalVotantes);
            System.out.println("Votos emitidos: " + resultados.votosEmitidos);
            System.out.println("Participación: " + String.format("%.2f%%", resultados.participacion));
            System.out.println("Última actualización: " + resultados.ultimaActualizacion);
            
        } catch (Exception e) {
            System.out.println("ERROR obteniendo resultados: " + e.getMessage());
        }
    }
    
    /**
     * Gestión de suscripciones.
     */
    private void gestionarSuscripciones() {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("\n===== GESTIÓN DE SUSCRIPCIONES =====");
        System.out.println("1. Crear suscripción de prueba");
        System.out.println("2. Ver estadísticas de suscripciones");
        System.out.println("3. Limpiar suscripciones expiradas");
        System.out.print("Seleccione: ");
        
        String opcion = scanner.nextLine().trim();
        
        try {
            switch (opcion) {
                case "1":
                    crearSuscripcionPrueba();
                    break;
                case "2":
                    System.out.println(subscriberManager.obtenerEstadisticasSuscripciones());
                    break;
                case "3":
                    subscriberManager.limpiarSuscripcionesExpiradas();
                    System.out.println("Suscripciones expiradas limpiadas");
                    break;
                default:
                    System.out.println("Opción no válida");
            }
        } catch (Exception e) {
            System.out.println("ERROR en gestión: " + e.getMessage());
        }
    }
    
    /**
     * Crea una suscripción de prueba.
     */
    private void crearSuscripcionPrueba() throws Exception {
        String clientId = "cliente_test_" + System.currentTimeMillis();
        
        FiltroResultados filtros = new FiltroResultados();
        filtros.tipo = TipoFiltro.REGION;
        filtros.region = "TODAS";
        filtros.valor = "";
        filtros.incluirDetalle = true;
        
        SuscripcionResultados suscripcion = visualizadorResultados.suscribirseResultados(clientId, filtros, null);
        
        System.out.println("Suscripción creada:");
        System.out.println("- ID: " + suscripcion.suscripcionId);
        System.out.println("- Cliente: " + suscripcion.clientId);
        System.out.println("- Región: " + suscripcion.region);
        System.out.println("- Estado: " + suscripcion.estado);
    }
    
    /**
     * Permite publicar actualización manual.
     */
    private void publicarActualizacion() {
        Scanner scanner = new Scanner(System.in);
        
        try {
            System.out.print("Ingrese región: ");
            String region = scanner.nextLine().trim();
            
            if (region.isEmpty()) {
                region = "GENERAL";
            }
            
            // Generar resultados de ejemplo
            String resultados = String.format(
                "{\"region\":\"%s\",\"timestamp\":\"%s\",\"votos_actualizados\":true}",
                region, java.time.LocalDateTime.now()
            );
            
            publisherResultados.publicarActualizacion(resultados, region, null);
            System.out.println("Actualización publicada para región: " + region);
            
        } catch (Exception e) {
            System.out.println("ERROR publicando: " + e.getMessage());
        }
    }
    
    /**
     * Limpia el cache de resultados.
     */
    private void limpiarCacheResultados() {
        try {
            resultadosCache.limpiarCacheResultados(null);
            System.out.println("Cache de resultados limpiado exitosamente");
            
        } catch (Exception e) {
            System.out.println("ERROR limpiando cache: " + e.getMessage());
        }
    }
    
    /**
     * Verifica conectividad con servicios externos.
     */
    private void verificarConectividad() {
        System.out.println("\n===== VERIFICACIÓN DE CONECTIVIDAD =====");
        
        try {
            // Verificar PortalConsultas -> ServidorRegional
            boolean regionalOk = portalConsultas.probarConectividad();
            System.out.println("ServidorRegional: " + (regionalOk ? "OK" : "ERROR"));
            
            // Verificar PublisherResultados -> DatabaseProxy
            boolean databaseOk = publisherResultados.probarConectividad();
            System.out.println("DatabaseProxy: " + (databaseOk ? "OK" : "ERROR"));
            
            // Verificar servicios internos
            boolean serviciosOk = visualizadorResultados.verificarDisponibilidadServicios();
            System.out.println("Servicios internos: " + (serviciosOk ? "OK" : "ERROR"));
            
        } catch (Exception e) {
            System.out.println("ERROR verificando conectividad: " + e.getMessage());
        }
    }
    
    /**
     * Configuración avanzada del sistema.
     */
    private void configuracionAvanzada() {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("\n===== CONFIGURACIÓN AVANZADA =====");
        System.out.println("1. Mostrar configuración actual");
        System.out.println("2. Reiniciar métricas");
        System.out.println("3. Forzar actualización completa");
        System.out.println("4. Ver estado detallado");
        System.out.print("Seleccione: ");
        
        String opcion = scanner.nextLine().trim();
        
        try {
            switch (opcion) {
                case "1":
                    mostrarConfiguracionActual();
                    break;
                case "2":
                    reiniciarMetricas();
                    break;
                case "3":
                    forzarActualizacionCompleta();
                    break;
                case "4":
                    mostrarEstadoDetallado();
                    break;
                default:
                    System.out.println("Opción no válida");
            }
        } catch (Exception e) {
            System.out.println("ERROR en configuración: " + e.getMessage());
        }
    }
    
    /**
     * Muestra la configuración actual.
     */
    private void mostrarConfiguracionActual() {
        System.out.println("\n===== CONFIGURACIÓN ACTUAL =====");
        for (String key : configuracion.stringPropertyNames()) {
            System.out.println(key + " = " + configuracion.getProperty(key));
        }
    }
    
    /**
     * Reinicia todas las métricas.
     */
    private void reiniciarMetricas() {
        if (publisherResultados != null) {
            publisherResultados.reiniciarMetricas();
        }
        if (visualizadorResultados != null) {
            visualizadorResultados.reiniciarMetricas();
        }
        if (portalConsultas != null) {
            portalConsultas.reiniciarEstadisticas();
        }
        
        System.out.println("Métricas reiniciadas");
    }
    
    /**
     * Fuerza actualización completa del sistema.
     */
    private void forzarActualizacionCompleta() {
        System.out.println("Forzando actualización completa...");
        
        if (publisherResultados != null) {
            publisherResultados.forzarPublicacionCompleta();
        }
        
        System.out.println("Actualización completa ejecutada");
    }
    
    /**
     * Muestra estado detallado de todos los componentes.
     */
    private void mostrarEstadoDetallado() {
        System.out.println("\n===== ESTADO DETALLADO =====");
        
        if (publisherResultados != null) {
            System.out.println(publisherResultados.obtenerEstadoDetallado());
        }
        
        if (visualizadorResultados != null) {
            System.out.println(visualizadorResultados.obtenerEstadoDetallado());
        }
        
        if (portalConsultas != null) {
            System.out.println(portalConsultas.obtenerEstadoDetallado());
        }
    }
    
    /**
     * Optimiza el sistema completo.
     */
    private void optimizarSistema() {
        System.out.println("Optimizando sistema...");
        
        try {
            // Optimizar base de datos del cache
            if (resultadosCache != null) {
                resultadosCache.optimizarBaseDatos();
            }
            
            // Limpiar entradas expiradas
            if (subscriberManager != null) {
                subscriberManager.limpiarSuscripcionesExpiradas();
            }
            
            if (resultadosCache != null) {
                resultadosCache.limpiarEntradas();
            }
            
            // Forzar garbage collection
            System.gc();
            
            System.out.println("Optimización completada");
            
        } catch (Exception e) {
            System.out.println("ERROR optimizando: " + e.getMessage());
        }
    }
    
    /**
     * Registra estadísticas generales periódicamente.
     */
    private void registrarEstadisticasGenerales() {
        try {
            logger.info("=== ESTADISTICAS SERVIDOR WEB ===");
            
            if (resultadosCache != null) {
                logger.info(resultadosCache.obtenerEstadisticasDetalladas());
            }
            
            if (subscriberManager != null) {
                logger.info(subscriberManager.obtenerEstadisticasSuscripciones());
            }
            
            if (publisherResultados != null) {
                logger.info(publisherResultados.obtenerEstadisticasPublisher());
            }
            
        } catch (Exception e) {
            logger.error("Error registrando estadísticas: {}", e.getMessage());
        }
    }
    
    /**
     * Verifica conectividad con servicios externos.
     */
    private void verificarConectividadServicios() {
        try {
            boolean regionalOk = portalConsultas != null && portalConsultas.probarConectividad();
            boolean databaseOk = publisherResultados != null && publisherResultados.probarConectividad();
            
            if (!regionalOk || !databaseOk) {
                logger.warn("Problemas de conectividad - Regional: {}, Database: {}", 
                           regionalOk, databaseOk);
            }
            
        } catch (Exception e) {
            logger.error("Error verificando conectividad: {}", e.getMessage());
        }
    }
    
    /**
     * Cierra el servidor ordenadamente.
     */
    private void cerrarServidor() {
        logger.info("Cerrando ServidorWeb...");
        
        try {
            servidorActivo = false;
            
            // Cerrar servicios auxiliares
            if (executorMantenimiento != null && !executorMantenimiento.isShutdown()) {
                executorMantenimiento.shutdown();
                if (!executorMantenimiento.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorMantenimiento.shutdownNow();
                }
            }
            
            // Cerrar componentes
            if (publisherResultados != null) {
                publisherResultados.cerrar();
            }
            
            if (subscriberManager != null) {
                subscriberManager.cerrar();
            }
            
            if (resultadosCache != null) {
                resultadosCache.cerrar();
            }
            
            if (visualizadorResultados != null) {
                visualizadorResultados.cerrar();
            }
            
            if (portalConsultas != null) {
                portalConsultas.cerrar();
            }
            
            // Desactivar adaptador
            if (adapter != null) {
                adapter.deactivate();
            }
            
            // Cerrar comunicador
            if (communicator != null) {
                communicator.destroy();
            }
            
            logger.info("OK ServidorWeb cerrado exitosamente");
            
        } catch (Exception e) {
            logger.error("Error cerrando servidor: {}", e.getMessage());
        }
    }
} 