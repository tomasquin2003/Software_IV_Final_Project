package com.registraduria.votacion.regional;

import Votacion.AdministradorMesaRemotoPrx;
import Votacion.CacheRegionalPrx;
import Votacion.GestorConsultasRegionalPrx;

import com.registraduria.votacion.regional.cache.CacheRegionalImpl;
import com.registraduria.votacion.regional.consultas.GestorConsultasRegionalImpl;

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
 * ServidorRegionalApp - Aplicación principal del Servidor Regional.
 * 
 * Punto especializado para consultas regionales de lugares de votación y mesas asignadas,
 * optimizando el acceso mediante caching regional y colaborando con el ServidorCentral.
 * 
 * Integra: GestorConsultasRegional y CacheRegional.
 */
public class ServidorRegionalApp {
    private static final Logger logger = LoggerFactory.getLogger(ServidorRegionalApp.class);
    
    // Componentes del servidor
    private static GestorConsultasRegionalImpl gestorConsultas;
    private static CacheRegionalImpl cacheRegional;
    
    // ICE
    private static Communicator communicator;
    private static ObjectAdapter adapter;
    
    // Servicios auxiliares
    private static ScheduledExecutorService scheduledExecutor;
    
    // Configuración
    private static Properties serverConfig;
    private static String regionId;
    
    public static void main(String[] args) {
        try {
            logger.info("====================================");
            logger.info("    SERVIDOR REGIONAL INICIANDO    ");
            logger.info("====================================");
            
            // Cargar configuración
            serverConfig = cargarConfiguracion();
            regionId = serverConfig.getProperty("servidor.regionId", "REGION_01");
            
            // Inicializar ICE
            inicializarICE(serverConfig);
            
            // Inicializar componentes
            inicializarComponentes(serverConfig);
            
            // Configurar servicios
            configurarServicios();
            
            // Activar adapter
            adapter.activate();
            
            logger.info("OK ServidorRegional iniciado exitosamente");
            logger.info("Región: {}", regionId);
            logger.info("Puerto: {}", serverConfig.getProperty("servidor.puerto", "10005"));
            logger.info("Directorio datos: {}", serverConfig.getProperty("servidor.dataDir", "./data/regional"));
            
            // Mostrar menú administrativo
            mostrarMenuRegional();
            
        } catch (Exception e) {
            logger.error("ERROR Error iniciando ServidorRegional: {}", e.getMessage(), e);
            System.exit(1);
        } finally {
            shutdown();
        }
    }
    
    /**
     * Carga la configuración del servidor regional.
     */
    private static Properties cargarConfiguracion() throws IOException {
        Properties config = new Properties();
        
        // Configuración por defecto
        config.setProperty("servidor.puerto", "10005");
        config.setProperty("servidor.dataDir", "./data/regional");
        config.setProperty("servidor.nombre", "ServidorRegional");
        config.setProperty("servidor.regionId", "REGION_01");
        config.setProperty("servidor.cacheSize", "10000");
        config.setProperty("servidor.cacheTTL", "3600"); // 1 hora
        config.setProperty("servidorcentral.endpoint", "AdministradorMesa:tcp -h localhost -p 10003");
        
        // Intentar cargar archivo de configuración
        String archivoConfig = "./src/main/resources/config/regional.config";
        try {
            if (Files.exists(Paths.get(archivoConfig))) {
                config.load(Files.newInputStream(Paths.get(archivoConfig)));
                logger.info("Configuración cargada desde: {}", archivoConfig);
            } else {
                logger.info("Usando configuración por defecto");
                crearArchivoConfiguracion(archivoConfig, config);
            }
        } catch (Exception e) {
            logger.warn("Error cargando configuración, usando valores por defecto: {}", e.getMessage());
        }
        
        return config;
    }
    
    /**
     * Inicializa el comunicador ICE.
     */
    private static void inicializarICE(Properties config) {
        try {
            logger.info("Inicializando ICE...");
            
            // Crear communicator
            String[] initData = {
                "--Ice.Config.DefaultDir=" + System.getProperty("user.dir"),
            };
            communicator = Util.initialize(initData);
            
            // Crear adapter
            String puerto = config.getProperty("servidor.puerto", "10005");
            String endpoint = "tcp -p " + puerto;
            adapter = communicator.createObjectAdapterWithEndpoints("ServidorRegionalAdapter", endpoint);
            
            logger.info("ICE inicializado - Puerto: {}", puerto);
            
        } catch (Exception e) {
            logger.error("Error inicializando ICE: {}", e.getMessage());
            throw new RuntimeException("Error en inicialización ICE", e);
        }
    }
    
    /**
     * Inicializa todos los componentes del servidor regional.
     */
    private static void inicializarComponentes(Properties config) {
        try {
            logger.info("Inicializando componentes del ServidorRegional...");
            
            String dataDir = config.getProperty("servidor.dataDir", "./data/regional");
            int cacheSize = Integer.parseInt(config.getProperty("servidor.cacheSize", "10000"));
            int cacheTTL = Integer.parseInt(config.getProperty("servidor.cacheTTL", "3600"));
            
            // Crear directorio de datos si no existe
            Files.createDirectories(Paths.get(dataDir));
            Files.createDirectories(Paths.get(dataDir + "/cache"));
            
            // 1. CacheRegional
            cacheRegional = new CacheRegionalImpl(dataDir, regionId, cacheSize, cacheTTL);
            adapter.add(cacheRegional, Util.stringToIdentity("CacheRegional"));
            CacheRegionalPrx cacheRegionalPrx = CacheRegionalPrx.checkedCast(
                adapter.createProxy(Util.stringToIdentity("CacheRegional")));
            
            // 2. GestorConsultasRegional (principal)
            String endpointCentral = config.getProperty("servidorcentral.endpoint");
            gestorConsultas = new GestorConsultasRegionalImpl(
                communicator, cacheRegionalPrx, endpointCentral, regionId);
            adapter.add(gestorConsultas, Util.stringToIdentity("GestorConsultasRegional"));
            
            logger.info("OK Todos los componentes regionales inicializados exitosamente");
            
        } catch (Exception e) {
            logger.error("Error inicializando componentes: {}", e.getMessage());
            throw new RuntimeException("Error en inicialización de componentes", e);
        }
    }
    
    /**
     * Configura servicios auxiliares.
     */
    private static void configurarServicios() {
        try {
            logger.info("Configurando servicios auxiliares...");
            
            // Executor para tareas programadas
            scheduledExecutor = Executors.newScheduledThreadPool(2);
            
            // Limpieza automática del caché cada hora
            scheduledExecutor.scheduleAtFixedRate(
                () -> limpiarCacheAutomatico(),
                1, 1, TimeUnit.HOURS
            );
            
            // Estadísticas periódicas cada 30 minutos
            scheduledExecutor.scheduleAtFixedRate(
                () -> mostrarEstadisticasPeriodicas(),
                30, 30, TimeUnit.MINUTES
            );
            
            logger.info("OK Servicios auxiliares configurados");
            
        } catch (Exception e) {
            logger.error("Error configurando servicios: {}", e.getMessage());
        }
    }
    
    /**
     * Muestra el menú principal del servidor regional.
     */
    private static void mostrarMenuRegional() {
        Scanner scanner = new Scanner(System.in);
        
        while (true) {
            try {
                System.out.println("\n" + "=".repeat(50));
                System.out.println("    SERVIDOR REGIONAL - REGIÓN " + regionId);
                System.out.println("=".repeat(50));
                System.out.println("1. Ver estadísticas del caché");
                System.out.println("2. Consultar lugar de votación");
                System.out.println("3. Limpiar caché");
                System.out.println("4. Verificar estado del caché");
                System.out.println("5. Configurar región");
                System.out.println("6. Probar conectividad con ServidorCentral");
                System.out.println("7. Ver estadísticas detalladas");
                System.out.println("8. Ayuda");
                System.out.println("0. Salir");
                System.out.println("=".repeat(50));
                System.out.print("Seleccione una opción: ");
                
                String opcion = scanner.nextLine().trim();
                
                switch (opcion) {
                    case "1":
                        mostrarEstadisticasCache();
                        break;
                    case "2":
                        consultarLugarVotacion(scanner);
                        break;
                    case "3":
                        limpiarCache();
                        break;
                    case "4":
                        verificarEstadoCache();
                        break;
                    case "5":
                        configurarRegion(scanner);
                        break;
                    case "6":
                        probarConectividad();
                        break;
                    case "7":
                        mostrarEstadisticasDetalladas();
                        break;
                    case "8":
                        mostrarAyuda();
                        break;
                    case "0":
                        logger.info("Cerrando ServidorRegional...");
                        return;
                    default:
                        System.out.println("ERROR Opcion invalida");
                }
                
            } catch (Exception e) {
                logger.error("Error en menú: {}", e.getMessage());
                System.out.println("ERROR Error: " + e.getMessage());
            }
        }
    }
    
    /**
     * Muestra estadísticas del caché.
     */
    private static void mostrarEstadisticasCache() {
        try {
            System.out.println("\nESTADISTICAS DEL CACHE REGIONAL");
            System.out.println("=".repeat(40));
            
            if (cacheRegional != null) {
                var estadoCache = cacheRegional.verificarEstadoCache(null);
                
                System.out.println("Total entradas: " + estadoCache.totalEntradas);
                System.out.println("Entradas válidas: " + estadoCache.entradasValidas);
                System.out.println("Consultas exitosas: " + estadoCache.consultasExitosas);
                System.out.println("Fallos de caché: " + estadoCache.fallosCache);
                System.out.println("Última actualización: " + estadoCache.ultimaActualizacion);
                System.out.println("Estado: " + estadoCache.estado);
                
                if (estadoCache.totalEntradas > 0) {
                    double hitRate = (double) estadoCache.consultasExitosas / 
                                   (estadoCache.consultasExitosas + estadoCache.fallosCache) * 100;
                    System.out.printf("Tasa de aciertos: %.2f%%\n", hitRate);
                }
            } else {
                System.out.println("ERROR CacheRegional no disponible");
            }
            
        } catch (Exception e) {
            logger.error("Error mostrando estadísticas: {}", e.getMessage());
            System.out.println("ERROR Error obteniendo estadisticas: " + e.getMessage());
        }
    }
    
    /**
     * Consulta lugar de votación para una cédula.
     */
    private static void consultarLugarVotacion(Scanner scanner) {
        try {
            System.out.print("Ingrese la cédula: ");
            String cedula = scanner.nextLine().trim();
            
            if (cedula.isEmpty()) {
                System.out.println("ERROR Cedula no puede estar vacia");
                return;
            }
            
            System.out.println("Consultando lugar de votacion...");
            
            if (gestorConsultas != null) {
                var lugarVotacion = gestorConsultas.consultarLugarVotacion(cedula, null);
                
                System.out.println("\nOK LUGAR DE VOTACION ENCONTRADO");
                System.out.println("=".repeat(35));
                System.out.println("Mesa: " + lugarVotacion.mesaId);
                System.out.println("Dirección: " + lugarVotacion.direccion);
                System.out.println("Horario apertura: " + lugarVotacion.horarioApertura);
                System.out.println("Horario cierre: " + lugarVotacion.horarioCierre);
                System.out.println("Responsable: " + lugarVotacion.responsable);
                System.out.println("Teléfono: " + lugarVotacion.telefono);
                System.out.println("Activa: " + (lugarVotacion.activa ? "Sí" : "No"));
                
            } else {
                System.out.println("ERROR GestorConsultas no disponible");
            }
            
        } catch (Exception e) {
            logger.error("Error en consulta: {}", e.getMessage());
            System.out.println("ERROR Error consultando: " + e.getMessage());
        }
    }
    
    /**
     * Limpia el caché regional.
     */
    private static void limpiarCache() {
        try {
            System.out.println("Limpiando cache regional...");
            
            if (cacheRegional != null) {
                cacheRegional.limpiarCache(null);
                System.out.println("OK Cache limpiado exitosamente");
            } else {
                System.out.println("ERROR CacheRegional no disponible");
            }
            
        } catch (Exception e) {
            logger.error("Error limpiando caché: {}", e.getMessage());
            System.out.println("ERROR Error limpiando cache: " + e.getMessage());
        }
    }
    
    /**
     * Verifica el estado del caché.
     */
    private static void verificarEstadoCache() {
        try {
            System.out.println("Verificando estado del cache...");
            mostrarEstadisticasCache();
            
        } catch (Exception e) {
            logger.error("Error verificando caché: {}", e.getMessage());
            System.out.println("ERROR Error verificando cache: " + e.getMessage());
        }
    }
    
    /**
     * Configura parámetros de la región.
     */
    private static void configurarRegion(Scanner scanner) {
        try {
            System.out.println("\nCONFIGURACION DE REGION");
            System.out.println("=".repeat(30));
            System.out.println("Región actual: " + regionId);
            System.out.print("Nueva región (o Enter para mantener): ");
            
            String nuevaRegion = scanner.nextLine().trim();
            if (!nuevaRegion.isEmpty()) {
                regionId = nuevaRegion;
                serverConfig.setProperty("servidor.regionId", regionId);
                System.out.println("OK Region actualizada a: " + regionId);
            }
            
        } catch (Exception e) {
            logger.error("Error configurando región: {}", e.getMessage());
            System.out.println("ERROR Error configurando region: " + e.getMessage());
        }
    }
    
    /**
     * Prueba conectividad con el ServidorCentral.
     */
    private static void probarConectividad() {
        try {
            System.out.println("Probando conectividad con ServidorCentral...");
            
            if (gestorConsultas != null) {
                boolean conectado = gestorConsultas.probarConectividad();
                if (conectado) {
                    System.out.println("OK Conectividad exitosa con ServidorCentral");
                } else {
                    System.out.println("ERROR Sin conectividad con ServidorCentral");
                }
            } else {
                System.out.println("ERROR GestorConsultas no disponible");
            }
            
        } catch (Exception e) {
            logger.error("Error probando conectividad: {}", e.getMessage());
            System.out.println("ERROR Error probando conectividad: " + e.getMessage());
        }
    }
    
    /**
     * Muestra estadísticas detalladas.
     */
    private static void mostrarEstadisticasDetalladas() {
        try {
            System.out.println("\nESTADISTICAS DETALLADAS DEL SERVIDOR REGIONAL");
            System.out.println("=".repeat(60));
            
            System.out.println("Región: " + regionId);
            System.out.println("Puerto: " + serverConfig.getProperty("servidor.puerto"));
            System.out.println("Directorio datos: " + serverConfig.getProperty("servidor.dataDir"));
            
            // Estadísticas del caché
            mostrarEstadisticasCache();
            
            // Información de memoria
            Runtime runtime = Runtime.getRuntime();
            long memoriaTotal = runtime.totalMemory() / 1024 / 1024;
            long memoriaLibre = runtime.freeMemory() / 1024 / 1024;
            long memoriaUsada = memoriaTotal - memoriaLibre;
            
            System.out.println("\nMEMORIA");
            System.out.println("=".repeat(20));
            System.out.println("Memoria total: " + memoriaTotal + " MB");
            System.out.println("Memoria usada: " + memoriaUsada + " MB");
            System.out.println("Memoria libre: " + memoriaLibre + " MB");
            
        } catch (Exception e) {
            logger.error("Error mostrando estadísticas detalladas: {}", e.getMessage());
            System.out.println("ERROR Error: " + e.getMessage());
        }
    }
    
    /**
     * Muestra ayuda del sistema.
     */
    private static void mostrarAyuda() {
        System.out.println("\nAYUDA - SERVIDOR REGIONAL");
        System.out.println("=".repeat(40));
        System.out.println("El ServidorRegional proporciona:");
        System.out.println("• Consultas optimizadas de lugares de votación");
        System.out.println("• Caché regional para consultas frecuentes");
        System.out.println("• Conectividad con el ServidorCentral");
        System.out.println("\nFuncionalidades principales:");
        System.out.println("• Consultar lugar de votación por cédula");
        System.out.println("• Gestión automática del caché");
        System.out.println("• Estadísticas de rendimiento");
        System.out.println("• Configuración de región");
    }
    
    /**
     * Limpieza automática del caché.
     */
    private static void limpiarCacheAutomatico() {
        try {
            logger.info("Ejecutando limpieza automática del caché...");
            if (cacheRegional != null) {
                // Limpia entradas expiradas, no todo el caché
                var estadoBefore = cacheRegional.verificarEstadoCache(null);
                cacheRegional.limpiarEntradas();
                var estadoAfter = cacheRegional.verificarEstadoCache(null);
                
                int entradasEliminadas = estadoBefore.totalEntradas - estadoAfter.totalEntradas;
                if (entradasEliminadas > 0) {
                    logger.info("Limpieza automática completada: {} entradas eliminadas", entradasEliminadas);
                }
            }
        } catch (Exception e) {
            logger.error("Error en limpieza automática: {}", e.getMessage());
        }
    }
    
    /**
     * Muestra estadísticas periódicas.
     */
    private static void mostrarEstadisticasPeriodicas() {
        try {
            if (cacheRegional != null) {
                var estado = cacheRegional.verificarEstadoCache(null);
                logger.info("Estadisticas cache - Entradas: {}, Validas: {}, Aciertos: {}", 
                           estado.totalEntradas, estado.entradasValidas, estado.consultasExitosas);
            }
        } catch (Exception e) {
            logger.error("Error mostrando estadísticas periódicas: {}", e.getMessage());
        }
    }
    
    /**
     * Crea archivo de configuración por defecto.
     */
    private static void crearArchivoConfiguracion(String archivo, Properties config) throws IOException {
        Files.createDirectories(Paths.get(archivo).getParent());
        
        StringBuilder contenido = new StringBuilder();
        contenido.append("# Configuración del Servidor Regional\n");
        contenido.append("# Generado automáticamente\n\n");
        
        contenido.append("# Configuración del servidor\n");
        contenido.append("servidor.puerto=").append(config.getProperty("servidor.puerto")).append("\n");
        contenido.append("servidor.dataDir=").append(config.getProperty("servidor.dataDir")).append("\n");
        contenido.append("servidor.nombre=").append(config.getProperty("servidor.nombre")).append("\n");
        contenido.append("servidor.regionId=").append(config.getProperty("servidor.regionId")).append("\n");
        contenido.append("servidor.cacheSize=").append(config.getProperty("servidor.cacheSize")).append("\n");
        contenido.append("servidor.cacheTTL=").append(config.getProperty("servidor.cacheTTL")).append("\n\n");
        
        contenido.append("# Conexiones remotas\n");
        contenido.append("servidorcentral.endpoint=").append(config.getProperty("servidorcentral.endpoint")).append("\n");
        
        Files.write(Paths.get(archivo), contenido.toString().getBytes());
        logger.info("Archivo de configuración creado: {}", archivo);
    }
    
    /**
     * Cierre ordenado del servidor.
     */
    private static void shutdown() {
        logger.info("Iniciando cierre del ServidorRegional...");
        
        try {
            // Detener servicios programados
            if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
                scheduledExecutor.shutdown();
                if (!scheduledExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduledExecutor.shutdownNow();
                }
            }
            
            // Cerrar componentes
            if (cacheRegional != null) {
                cacheRegional.cerrar();
            }
            
            if (gestorConsultas != null) {
                gestorConsultas.cerrar();
            }
            
            // Cerrar ICE
            if (communicator != null) {
                communicator.destroy();
            }
            
            logger.info("OK ServidorRegional cerrado exitosamente");
            
        } catch (Exception e) {
            logger.error("Error durante el cierre: {}", e.getMessage());
        }
    }
} 