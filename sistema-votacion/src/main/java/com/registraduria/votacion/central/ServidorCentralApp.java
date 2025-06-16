package com.registraduria.votacion.central;

import Votacion.ConnectionManagerPrx;
import Votacion.RegistroVotosPrx;

import com.registraduria.votacion.central.admin.InterfazAdministrativaImpl;
import com.registraduria.votacion.central.candidatos.GestorCandidatosImpl;
import com.registraduria.votacion.central.controller.ControllerCentralImpl;
import com.registraduria.votacion.central.database.ConnectionManagerImpl;
import com.registraduria.votacion.central.mesa.AdministradorMesaImpl;
import com.registraduria.votacion.central.registro.RegistroVotosImpl;

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
 * ServidorCentralApp - Aplicación principal del Servidor Central.
 * 
 * Punto crítico de procesamiento y consolidación de votos, gestión de candidatos
 * y administración de mesas electorales.
 * 
 * Integra todos los componentes: ControllerCentral, RegistroVotos, GestorCandidatos,
 * AdministradorMesa e InterfazAdministrativa.
 */
public class ServidorCentralApp {
    private static final Logger logger = LoggerFactory.getLogger(ServidorCentralApp.class);
    
    // Componentes del servidor
    private static ControllerCentralImpl controllerCentral;
    private static RegistroVotosImpl registroVotos;
    private static GestorCandidatosImpl gestorCandidatos;
    private static AdministradorMesaImpl administradorMesa;
    private static InterfazAdministrativaImpl interfazAdministrativa;
    private static ConnectionManagerImpl connectionManager;
    
    // ICE
    private static Communicator communicator;
    private static ObjectAdapter adapter;
    
    // Servicios auxiliares
    private static ScheduledExecutorService scheduledExecutor;
    
    public static void main(String[] args) {
        try {
            logger.info("====================================");
            logger.info("    SERVIDOR CENTRAL INICIANDO     ");
            logger.info("====================================");
            
            // Cargar configuración
            Properties config = cargarConfiguracion();
            
            // Inicializar ICE
            inicializarICE(config);
            
            // Inicializar componentes
            inicializarComponentes(config);
            
            // Configurar servicios
            configurarServicios();
            
            // Activar adapter
            adapter.activate();
            
            logger.info("✅ ServidorCentral iniciado exitosamente");
            logger.info("Puerto: {}", config.getProperty("servidor.puerto", "10002"));
            logger.info("Directorio datos: {}", config.getProperty("servidor.dataDir", "./data/central"));
            
            // Mostrar menú administrativo
            mostrarMenuAdministrativo();
            
        } catch (Exception e) {
            logger.error("ERROR Error iniciando ServidorCentral: {}", e.getMessage(), e);
            System.exit(1);
        } finally {
            shutdown();
        }
    }
    
    /**
     * Carga la configuración del servidor.
     */
    private static Properties cargarConfiguracion() throws IOException {
        Properties config = new Properties();
        
        // Configuración por defecto
        config.setProperty("servidor.puerto", "10002");
        config.setProperty("servidor.dataDir", "./data/central");
        config.setProperty("servidor.nombre", "ServidorCentral");
        config.setProperty("votosbroker.endpoint", "VotosBroker:tcp -p 10001");
        config.setProperty("database.endpoint", "DatabaseProxy:tcp -p 10004");
        
        // Intentar cargar archivo de configuración
        String archivoConfig = "./src/main/resources/config/central.config";
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
            String puerto = config.getProperty("servidor.puerto", "10002");
            String endpoint = "tcp -p " + puerto;
            adapter = communicator.createObjectAdapterWithEndpoints("ServidorCentralAdapter", endpoint);
            
            logger.info("ICE inicializado - Puerto: {}", puerto);
            
        } catch (Exception e) {
            logger.error("Error inicializando ICE: {}", e.getMessage());
            throw new RuntimeException("Error en inicialización ICE", e);
        }
    }
    
    /**
     * Inicializa todos los componentes del servidor.
     */
    private static void inicializarComponentes(Properties config) {
        try {
            logger.info("Inicializando componentes del ServidorCentral...");
            
            String dataDir = config.getProperty("servidor.dataDir", "./data/central");
            
            // Crear directorio de datos si no existe
            Files.createDirectories(Paths.get(dataDir));
            
            // 1. ConnectionManager (base de datos)
            connectionManager = new ConnectionManagerImpl(dataDir);
            adapter.add(connectionManager, Util.stringToIdentity("ConnectionManager"));
            ConnectionManagerPrx connectionManagerPrx = ConnectionManagerPrx.checkedCast(
                adapter.createProxy(Util.stringToIdentity("ConnectionManager")));
            
            // 2. RegistroVotos
            registroVotos = new RegistroVotosImpl(connectionManagerPrx, dataDir);
            adapter.add(registroVotos, Util.stringToIdentity("RegistroVotos"));
            RegistroVotosPrx registroVotosPrx = RegistroVotosPrx.checkedCast(
                adapter.createProxy(Util.stringToIdentity("RegistroVotos")));
            
            // 3. ControllerCentral (principal)
            controllerCentral = new ControllerCentralImpl(registroVotosPrx);
            adapter.add(controllerCentral, Util.stringToIdentity("ControllerCentral"));
            
            // 4. GestorCandidatos
            gestorCandidatos = new GestorCandidatosImpl(connectionManagerPrx, dataDir);
            adapter.add(gestorCandidatos, Util.stringToIdentity("GestorCandidatos"));
            
            // 5. AdministradorMesa
            administradorMesa = new AdministradorMesaImpl(dataDir);
            adapter.add(administradorMesa, Util.stringToIdentity("AdministradorMesa"));
            
            // 6. InterfazAdministrativa
            interfazAdministrativa = new InterfazAdministrativaImpl(
                gestorCandidatos, administradorMesa, dataDir);
            adapter.add(interfazAdministrativa, Util.stringToIdentity("InterfazAdministrativa"));
            
            logger.info("✅ Todos los componentes inicializados exitosamente");
            
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
            
            // Tarea de monitoreo cada 30 segundos
            scheduledExecutor.scheduleAtFixedRate(() -> {
                try {
                    mostrarEstadisticasPeriodicas();
                } catch (Exception e) {
                    logger.error("Error en monitoreo periódico: {}", e.getMessage());
                }
            }, 30, 30, TimeUnit.SECONDS);
            
            // Tarea de backup cada 5 minutos
            scheduledExecutor.scheduleAtFixedRate(() -> {
                try {
                    realizarBackupPeriodico();
                } catch (Exception e) {
                    logger.error("Error en backup periódico: {}", e.getMessage());
                }
            }, 5, 5, TimeUnit.MINUTES);
            
            logger.info("✅ Servicios auxiliares configurados");
            
        } catch (Exception e) {
            logger.error("Error configurando servicios: {}", e.getMessage());
        }
    }
    
    /**
     * Muestra el menú administrativo interactivo.
     */
    private static void mostrarMenuAdministrativo() {
        Scanner scanner = new Scanner(System.in);
        boolean continuar = true;
        
        while (continuar) {
            try {
                mostrarMenuPrincipal();
                
                System.out.print("Seleccione una opción: ");
                String opcion = scanner.nextLine().trim();
                
                switch (opcion) {
                    case "1":
                        mostrarEstadisticasDetalladas();
                        break;
                    case "2":
                        gestionarCandidatos();
                        break;
                    case "3":
                        gestionarMesas();
                        break;
                    case "4":
                        importarCandidatos();
                        break;
                    case "5":
                        visualizarDistribucionMesas();
                        break;
                    case "6":
                        validarUnicidadCandidatos();
                        break;
                    case "7":
                        realizarBackupManual();
                        break;
                    case "8":
                        mostrarAyuda();
                        break;
                    case "0":
                        System.out.println("Cerrando ServidorCentral...");
                        continuar = false;
                        break;
                    default:
                        System.out.println("ERROR Opcion invalida. Intente nuevamente.");
                }
                
                if (continuar) {
                    System.out.println("\nPresione Enter para continuar...");
                    scanner.nextLine();
                }
                
            } catch (Exception e) {
                logger.error("Error en menu administrativo: {}", e.getMessage());
                System.out.println("ERROR Error: " + e.getMessage());
            }
        }
        
        scanner.close();
    }
    
    /**
     * Muestra el menú principal.
     */
    private static void mostrarMenuPrincipal() {
        System.out.println("\n" + "=".repeat(50));
        System.out.println("       SERVIDOR CENTRAL - MENU PRINCIPAL");
        System.out.println("=".repeat(50));
        System.out.println("1. [ESTADISTICAS] Ver estadisticas detalladas");
        System.out.println("2. [CANDIDATOS] Gestionar candidatos");
        System.out.println("3. [MESAS] Gestionar mesas electorales");
        System.out.println("4. [IMPORTAR] Importar candidatos desde Excel");
        System.out.println("5. [DISTRIBUCION] Visualizar distribucion de mesas");
        System.out.println("6. [VALIDAR] Validar unicidad de candidatos");
        System.out.println("7. [BACKUP] Realizar backup manual");
        System.out.println("8. [AYUDA] Ayuda");
        System.out.println("0. [SALIR] Salir");
        System.out.println("=".repeat(50));
    }
    
    /**
     * Muestra estadisticas detalladas.
     */
    private static void mostrarEstadisticasDetalladas() {
        System.out.println("\n[ESTADISTICAS] ESTADISTICAS DETALLADAS DEL SISTEMA");
        System.out.println("=".repeat(60));
        
        try {
            if (controllerCentral != null) {
                System.out.println("[CONTROLLER] ControllerCentral:");
                System.out.println("   " + controllerCentral.obtenerMetricas());
            }
            
            if (registroVotos != null) {
                System.out.println("[REGISTRO] RegistroVotos:");
                System.out.println("   " + registroVotos.obtenerMetricas());
            }
            
            if (gestorCandidatos != null) {
                System.out.println("[CANDIDATOS] GestorCandidatos:");
                System.out.println("   " + gestorCandidatos.obtenerEstadisticas());
            }
            
            if (administradorMesa != null) {
                System.out.println("[MESAS] AdministradorMesa:");
                System.out.println("   " + administradorMesa.obtenerEstadisticas());
            }
            
        } catch (Exception e) {
            System.out.println("ERROR Error obteniendo estadisticas: " + e.getMessage());
        }
    }
    
    /**
     * Gestionar candidatos.
     */
    private static void gestionarCandidatos() {
        try {
            System.out.println("\n[CANDIDATOS] GESTION DE CANDIDATOS");
            gestorCandidatos.gestionarCandidatos(null);
            
            // Mostrar estadisticas de candidatos
            System.out.println("\n[ESTADISTICAS] Estadisticas:");
            System.out.println(gestorCandidatos.obtenerEstadisticas());
            
        } catch (Exception e) {
            System.out.println("ERROR Error gestionando candidatos: " + e.getMessage());
        }
    }
    
    /**
     * Gestionar mesas electorales.
     */
    private static void gestionarMesas() {
        try {
            System.out.println("\n[MESAS] GESTION DE MESAS ELECTORALES");
            administradorMesa.configurarMesas(null);
            
            // Mostrar asignaciones
            String asignaciones = administradorMesa.consultarAsignacionMesas(null);
            System.out.println("\n[ASIGNACIONES] Asignaciones actuales:");
            System.out.println(asignaciones);
            
        } catch (Exception e) {
            System.out.println("ERROR Error gestionando mesas: " + e.getMessage());
        }
    }
    
    /**
     * Importar candidatos desde Excel.
     */
    private static void importarCandidatos() {
        try {
            System.out.println("\n[IMPORTAR] IMPORTACION DE CANDIDATOS");
            System.out.println("Iniciando importacion desde Excel...");
            
            var estado = gestorCandidatos.importacionCandidatosExcel(null);
            System.out.println("Estado de importacion: " + estado);
            
            // Esperar un momento y mostrar resultado
            Thread.sleep(3000);
            System.out.println("\nEstadisticas despues de importacion:");
            System.out.println(gestorCandidatos.obtenerEstadisticas());
            
        } catch (Exception e) {
            System.out.println("ERROR Error importando candidatos: " + e.getMessage());
        }
    }
    
    /**
     * Visualizar distribucion de mesas.
     */
    private static void visualizarDistribucionMesas() {
        try {
            System.out.println("\n[DISTRIBUCION] DISTRIBUCION DE MESAS ELECTORALES");
            String distribucion = administradorMesa.visualizarDistribucion(null);
            System.out.println(distribucion);
            
        } catch (Exception e) {
            System.out.println("ERROR Error visualizando distribucion: " + e.getMessage());
        }
    }
    
    /**
     * Validar unicidad de candidatos.
     */
    private static void validarUnicidadCandidatos() {
        try {
            System.out.println("\n[VALIDAR] VALIDACION DE UNICIDAD DE CANDIDATOS");
            System.out.println("Ejecutando validacion...");
            
            boolean resultado = gestorCandidatos.validarCandidatosUnicos(null);
            
            if (resultado) {
                System.out.println("OK Validacion exitosa: Todos los candidatos son unicos");
            } else {
                System.out.println("ERROR Validacion fallo: Se encontraron candidatos duplicados");
            }
            
        } catch (Exception e) {
            System.out.println("ERROR Error en validacion: " + e.getMessage());
        }
    }
    
    /**
     * Realizar backup manual.
     */
    private static void realizarBackupManual() {
        try {
            System.out.println("\n[BACKUP] BACKUP MANUAL DEL SISTEMA");
            System.out.println("Realizando backup...");
            
            realizarBackupPeriodico();
            
            System.out.println("OK Backup completado exitosamente");
            
        } catch (Exception e) {
            System.out.println("ERROR Error realizando backup: " + e.getMessage());
        }
    }
    
    /**
     * Mostrar ayuda.
     */
    private static void mostrarAyuda() {
        System.out.println("\n[AYUDA] AYUDA DEL SISTEMA");
        System.out.println("=".repeat(50));
        System.out.println("ServidorCentral v1.0 - Sistema Electoral");
        System.out.println("");
        System.out.println("COMPONENTES:");
        System.out.println("• ControllerCentral: Recibe votos del VotosBroker");
        System.out.println("• RegistroVotos: Persiste votos anonimizados");
        System.out.println("• GestorCandidatos: Administra catalogo de candidatos");
        System.out.println("• AdministradorMesa: Gestiona mesas electorales");
        System.out.println("• InterfazAdministrativa: Panel de control");
        System.out.println("");
        System.out.println("FUNCIONALIDADES:");
        System.out.println("• Recepcion y procesamiento de votos");
        System.out.println("• Anonimizacion y persistencia");
        System.out.println("• Gestion de candidatos y mesas");
        System.out.println("• Importacion masiva desde Excel");
        System.out.println("• Validacion de unicidad");
        System.out.println("• Auditoria exhaustiva");
        System.out.println("=".repeat(50));
    }
    
    /**
     * Muestra estadisticas periodicas.
     */
    private static void mostrarEstadisticasPeriodicas() {
        try {
            logger.debug("=== ESTADISTICAS PERIODICAS ===");
            
            if (controllerCentral != null) {
                logger.debug("ControllerCentral: {}", controllerCentral.obtenerMetricas());
            }
            if (registroVotos != null) {
                logger.debug("RegistroVotos: {}", registroVotos.obtenerMetricas());
            }
            if (gestorCandidatos != null) {
                logger.debug("GestorCandidatos: {}", gestorCandidatos.obtenerEstadisticas());
            }
            if (administradorMesa != null) {
                logger.debug("AdministradorMesa: {}", administradorMesa.obtenerEstadisticas());
            }
            
        } catch (Exception e) {
            logger.error("Error en estadisticas periodicas: {}", e.getMessage());
        }
    }
    
    /**
     * Realiza backup periodico.
     */
    private static void realizarBackupPeriodico() {
        try {
            logger.info("Realizando backup periodico...");
            
            // Guardar datos de candidatos
            if (gestorCandidatos != null) {
                gestorCandidatos.guardarCandidatos(null);
            }
            
            // Guardar datos de votos
            if (connectionManager != null) {
                connectionManager.guardarCandidatos(null);
            }
            
            logger.info("Backup periodico completado");
            
        } catch (Exception e) {
            logger.error("Error en backup periodico: {}", e.getMessage());
        }
    }
    
    /**
     * Crea archivo de configuración de ejemplo.
     */
    private static void crearArchivoConfiguracion(String archivo, Properties config) throws IOException {
        StringBuilder contenido = new StringBuilder();
        contenido.append("# Configuración del ServidorCentral\n");
        contenido.append("servidor.puerto=10002\n");
        contenido.append("servidor.dataDir=./data/central\n");
        contenido.append("servidor.nombre=ServidorCentral\n");
        contenido.append("votosbroker.endpoint=VotosBroker:tcp -p 10001\n");
        contenido.append("database.endpoint=DatabaseProxy:tcp -p 10004\n");
        
        Files.createDirectories(Paths.get(archivo).getParent());
        Files.write(Paths.get(archivo), contenido.toString().getBytes());
        
        logger.info("Archivo de configuración creado: {}", archivo);
    }
    
    /**
     * Finaliza el servidor y libera recursos.
     */
    private static void shutdown() {
        try {
            logger.info("Finalizando ServidorCentral...");
            
            // Finalizar componentes
            if (controllerCentral != null) {
                controllerCentral.shutdown();
            }
            if (registroVotos != null) {
                registroVotos.shutdown();
            }
            if (gestorCandidatos != null) {
                gestorCandidatos.shutdown();
            }
            if (administradorMesa != null) {
                administradorMesa.shutdown();
            }
            if (interfazAdministrativa != null) {
                interfazAdministrativa.shutdown();
            }
            if (connectionManager != null) {
                connectionManager.shutdown();
            }
            
            // Finalizar servicios
            if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
                scheduledExecutor.shutdown();
            }
            
            // Finalizar ICE
            if (communicator != null) {
                communicator.destroy();
            }
            
            logger.info("✅ ServidorCentral finalizado exitosamente");
            
        } catch (Exception e) {
            logger.error("Error durante finalización: {}", e.getMessage());
        }
    }
} 