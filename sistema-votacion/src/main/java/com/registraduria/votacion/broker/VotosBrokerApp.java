package com.registraduria.votacion.broker;

import Votacion.BrokerManager;
import Votacion.CircuitBreakerService;
import Votacion.ControllerCentralPrx;
import Votacion.PersistenceManager;
import Votacion.QueueService;
import Votacion.RetryHandler;

import com.registraduria.votacion.broker.circuit.CircuitBreakerServiceImpl;
import com.registraduria.votacion.broker.manager.BrokerManagerImpl;
import com.registraduria.votacion.broker.persistence.PersistenceManagerImpl;
import com.registraduria.votacion.broker.queue.QueueServiceImpl;
import com.registraduria.votacion.broker.retry.RetryHandlerImpl;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Identity;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;

/**
 * Aplicación principal del VotosBroker - Microservicio intermedio robusto
 * para transmisión confiable de votos entre CentroVotación y ServidorCentral.
 */
public class VotosBrokerApp {
    private static final Logger logger = LoggerFactory.getLogger(VotosBrokerApp.class);
    
    private static final String DEFAULT_CONFIG_FILE = "src/main/resources/config/broker.config";
    private static final String DEFAULT_ENDPOINTS = "tcp -h localhost -p 10002";
    
    // Executor para operaciones asíncronas
    private static ScheduledExecutorService asyncExecutor;
    
    /**
     * Método principal que inicia el VotosBroker.
     * 
     * @param args Argumentos de línea de comandos
     */
    public static void main(String[] args) {
        logger.info("=== INICIANDO VOTOSBROKER ===");
        logger.info("Microservicio intermedio de transmisión confiable de votos");
        
        String configFile = DEFAULT_CONFIG_FILE;
        
        // Procesar argumentos de línea de comandos
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--config") && i + 1 < args.length) {
                configFile = args[i + 1];
                i++;
            }
        }
        
        logger.info("Usando archivo de configuración: {}", configFile);
        
        // Inicializar executor para operaciones asíncronas
        asyncExecutor = Executors.newScheduledThreadPool(10);
        
        try (Communicator communicator = Util.initialize(args)) {
            
            // Cargar configuración
            Properties props = loadConfiguration(configFile);
            
            // Configurar endpoints y nombres de servants
            String endpoints = props.getProperty("VotosBroker.Endpoints", DEFAULT_ENDPOINTS);
            String brokerManagerServant = props.getProperty("VotosBroker.BrokerManagerServant", "BrokerManager");
            String queueServiceServant = props.getProperty("VotosBroker.QueueServiceServant", "QueueService");
            String persistenceManagerServant = props.getProperty("VotosBroker.PersistenceManagerServant", "PersistenceManager");
            String circuitBreakerServant = props.getProperty("VotosBroker.CircuitBreakerServiceServant", "CircuitBreakerService");
            String retryHandlerServant = props.getProperty("VotosBroker.RetryHandlerServant", "RetryHandler");
            
            // Configuración de conexión con ServidorCentral
            String servidorCentralProxy = props.getProperty("ServidorCentral.ControllerCentralProxy", 
                "ControllerCentral:tcp -h localhost -p 10003");
            
            // Rutas de archivos de persistencia
            String votosPendientesDb = props.getProperty("VotosBroker.VotosPendientesDb", 
                "data/broker/VotosPendientesEnvio.db");
            String brokerLogsDb = props.getProperty("VotosBroker.BrokerLogsDb", 
                "data/broker/BrokerLogs.db");
            
            // Crear adaptador del VotosBroker
            ObjectAdapter adapter = communicator.createObjectAdapterWithEndpoints("VotosBrokerAdapter", endpoints);
            
            // Verificar y crear directorios de datos
            createDataDirectories();
            
            // === INICIALIZAR COMPONENTES INTERNOS ===
            
            // 1. CircuitBreakerService
            CircuitBreakerServiceImpl circuitBreakerImpl = new CircuitBreakerServiceImpl(props);
            Identity circuitBreakerId = new Identity(circuitBreakerServant, "");
            adapter.add((CircuitBreakerService) circuitBreakerImpl, circuitBreakerId);
            
            // 2. PersistenceManager  
            PersistenceManagerImpl persistenceImpl = new PersistenceManagerImpl(votosPendientesDb, brokerLogsDb);
            Identity persistenceId = new Identity(persistenceManagerServant, "");
            adapter.add((PersistenceManager) persistenceImpl, persistenceId);
            
            // 3. QueueService
            QueueServiceImpl queueImpl = new QueueServiceImpl(persistenceImpl);
            Identity queueId = new Identity(queueServiceServant, "");
            adapter.add((QueueService) queueImpl, queueId);
            
            // 4. Obtener proxy del ServidorCentral
            ControllerCentralPrx controllerCentralPrx = ControllerCentralPrx.checkedCast(
                communicator.stringToProxy(servidorCentralProxy));
            
            if (controllerCentralPrx == null) {
                throw new RuntimeException("Proxy inválido para ServidorCentral: " + servidorCentralProxy);
            }
            
            // 5. RetryHandler
            RetryHandlerImpl retryImpl = new RetryHandlerImpl(
                queueImpl, persistenceImpl, circuitBreakerImpl, controllerCentralPrx, asyncExecutor, props);
            Identity retryId = new Identity(retryHandlerServant, "");
            adapter.add((RetryHandler) retryImpl, retryId);
            
            // 6. BrokerManager (interfaz principal)
            BrokerManagerImpl brokerManagerImpl = new BrokerManagerImpl(
                queueImpl, persistenceImpl, circuitBreakerImpl, controllerCentralPrx, retryImpl);
            Identity brokerManagerId = new Identity(brokerManagerServant, "");
            adapter.add((BrokerManager) brokerManagerImpl, brokerManagerId);
            
            // Activar el adaptador
            adapter.activate();
            
            logger.info("=== VOTOSBROKER INICIADO EXITOSAMENTE ===");
            logger.info("Endpoints: {}", endpoints);
            logger.info("ServidorCentral: {}", servidorCentralProxy);
            logger.info("Base de datos votos pendientes: {}", votosPendientesDb);
            logger.info("Base de datos logs: {}", brokerLogsDb);
            
            // Procesar votos pendientes al inicio
            logger.info("Procesando votos pendientes al inicio...");
            System.out.println("Procesando votos pendientes...");
            retryImpl.procesarVotosPendientes(null);
            System.out.println("Votos pendientes procesados");
            
            // Crear thread para la interfaz de usuario
            Thread uiThread = createUIThread(brokerManagerImpl, retryImpl, circuitBreakerImpl);
            uiThread.start();
            
            // Esperar shutdown
            logger.info("VotosBroker listo para recibir peticiones. Presione Ctrl+C para finalizar.");
            communicator.waitForShutdown();
            
        } catch (Exception e) {
            logger.error("Error fatal en VotosBroker", e);
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            if (asyncExecutor != null) {
                asyncExecutor.shutdown();
            }
        }
        
        logger.info("=== VOTOSBROKER FINALIZADO ===");
    }
    
    /**
     * Carga la configuración desde el archivo especificado.
     */
    private static Properties loadConfiguration(String configFile) {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(configFile)) {
            props.load(fis);
            logger.info("Configuración cargada desde: {}", configFile);
        } catch (Exception e) {
            logger.warn("No se pudo cargar la configuración desde {}, usando valores por defecto", configFile);
        }
        return props;
    }
    
    /**
     * Crea los directorios necesarios para la persistencia.
     */
    private static void createDataDirectories() {
        File brokerDataDir = new File("data/broker");
        if (!brokerDataDir.exists()) {
            brokerDataDir.mkdirs();
            logger.info("Directorio de datos del broker creado: {}", brokerDataDir.getAbsolutePath());
        }
    }
    
    /**
     * Crea el thread de la interfaz de usuario para monitoreo y administración.
     */
    private static Thread createUIThread(BrokerManagerImpl brokerManager, 
                                       RetryHandlerImpl retryHandler,
                                       CircuitBreakerServiceImpl circuitBreaker) {
        return new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
                while (true) {
                    System.out.println("\n=== VOTOSBROKER - MENÚ DE ADMINISTRACIÓN ===");
                    System.out.println("1. Ver estado del sistema");
                    System.out.println("2. Ver votos pendientes");
                    System.out.println("3. Ver estado del Circuit Breaker");
                    System.out.println("4. Forzar procesamiento de pendientes");
                    System.out.println("5. Resetear Circuit Breaker");
                    System.out.println("6. Salir");
                    System.out.print("Seleccione una opción: ");
                    
                    String opcion = reader.readLine();
                    if (opcion == null) break;
                    
                    opcion = opcion.trim();
                    
                    try {
                        switch (opcion) {
                            case "1":
                                mostrarEstadoSistema(brokerManager);
                                break;
                            case "2":
                                mostrarVotosPendientes(brokerManager);
                                break;
                            case "3":
                                mostrarEstadoCircuitBreaker(circuitBreaker);
                                break;
                            case "4":
                                forzarProcesamientoPendientes(retryHandler);
                                break;
                            case "5":
                                resetearCircuitBreaker(circuitBreaker);
                                break;
                            case "6":
                                logger.info("Cerrando VotosBroker por solicitud del usuario...");
                                System.exit(0);
                                return;
                            default:
                                System.out.println("Opción no válida.");
                        }
                    } catch (Exception e) {
                        System.out.println("Error procesando opción: " + e.getMessage());
                        logger.error("Error en UI", e);
                    }
                }
            } catch (Exception e) {
                logger.error("Error fatal en thread UI", e);
            }
        });
    }
    
    private static void mostrarEstadoSistema(BrokerManagerImpl brokerManager) {
        System.out.println("\n=== ESTADO DEL SISTEMA ===");
        // Implementar métricas del broker
        System.out.println("VotosBroker operativo");
        System.out.println("Conexiones activas: Verificando...");
    }
    
    private static void mostrarVotosPendientes(BrokerManagerImpl brokerManager) {
        System.out.println("\n=== VOTOS PENDIENTES ===");
        // Implementar listado de votos pendientes
        System.out.println("Consultando votos pendientes...");
    }
    
    private static void mostrarEstadoCircuitBreaker(CircuitBreakerServiceImpl circuitBreaker) {
        System.out.println("\n=== ESTADO CIRCUIT BREAKER ===");
        // Implementar estado del circuit breaker
        System.out.println("Estado: Verificando...");
    }
    
    private static void forzarProcesamientoPendientes(RetryHandlerImpl retryHandler) {
        System.out.println("\n=== FORZANDO PROCESAMIENTO ===");
        try {
            retryHandler.procesarVotosPendientes(null);
            System.out.println("Procesamiento iniciado exitosamente.");
        } catch (Exception e) {
            System.out.println("Error al forzar procesamiento: " + e.getMessage());
        }
    }
    
    private static void resetearCircuitBreaker(CircuitBreakerServiceImpl circuitBreaker) {
        System.out.println("\n=== RESETEANDO CIRCUIT BREAKER ===");
        try {
            circuitBreaker.resetearCircuitBreaker("ServidorCentral");
            System.out.println("Circuit Breaker reseteado exitosamente.");
        } catch (Exception e) {
            System.out.println("Error al resetear Circuit Breaker: " + e.getMessage());
        }
    }
} 