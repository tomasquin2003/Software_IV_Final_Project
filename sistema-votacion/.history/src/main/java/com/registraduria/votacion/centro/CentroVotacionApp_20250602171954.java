package com.registraduria.votacion.centro;

import Votacion.AlmacenamientoVotos;
import Votacion.AlmacenamientoVotosPrx;
import Votacion.GestorRecepcionVotos;
import Votacion.MotorEmisionVotos;
import Votacion.MotorEmisionVotosPrx;
import Votacion.ValidadorDeVotos;
import Votacion.ValidadorDeVotosPrx;

import com.registraduria.votacion.centro.persistencia.AlmacenamientoVotosImpl;
import com.registraduria.votacion.centro.procesamiento.MotorEmisionVotosImpl;
import com.registraduria.votacion.centro.recepcion.GestorRecepcionVotosImpl;
import com.registraduria.votacion.centro.validacion.ValidadorDeVotosImpl;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Identity;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;
import java.util.Scanner;

/**
 * Aplicación principal para el Centro de Votación.
 */
public class CentroVotacionApp {
    private static final Logger logger = LoggerFactory.getLogger(CentroVotacionApp.class);
    
    /**
     * Método principal que inicia la aplicación.
     * 
     * @param args Argumentos de línea de comandos
     */
    public static void main(String[] args) {
        logger.info("Iniciando Centro de Votación");
        
        // Ruta del archivo de configuración
        String configFile = "src/main/resources/config/centro.config";
        
        // Procesar argumentos de línea de comandos
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--config") && i + 1 < args.length) {
                configFile = args[i + 1];
                i++;
            }
        }
        
        logger.info("Usando archivo de configuración: {}", configFile);
        
        // Crear instancia de Ice Communicator
        try (Communicator communicator = Util.initialize(args)) {
            
            // Cargar propiedades desde el archivo de configuración
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(configFile)) {
                props.load(fis);
            }
            
            // Obtener endpoints y nombres de los servants desde la configuración
            String endpoints = props.getProperty("CentroVotacion.Endpoints", "tcp -h localhost -p 10001");
            String gestorRecepcionServantName = props.getProperty("CentroVotacion.GestorRecepcionVotosServant", "GestorRecepcionVotos");
            String almacenamientoServantName = props.getProperty("CentroVotacion.AlmacenamientoVotosServant", "AlmacenamientoVotos");
            String validadorServantName = props.getProperty("CentroVotacion.ValidadorDeVotosServant", "ValidadorDeVotos");
            String motorEmisionServantName = props.getProperty("CentroVotacion.MotorEmisionVotosServant", "MotorEmisionVotos");
            
            // Rutas de archivos de datos
            String candidatosFile = props.getProperty("CentroVotacion.CandidatosFile", "src/main/resources/data/Candidatos.csv");
            String votosRecibidosFile = props.getProperty("CentroVotacion.VotosRecibidosFile", "data/centro/VotosRecibidos.csv");
            String resultadosFile = props.getProperty("CentroVotacion.ResultadosFile", "data/centro/Resultados.csv");
            
            // Crear adaptador para el Centro de Votación
            ObjectAdapter adapter = communicator.createObjectAdapterWithEndpoints("CentroVotacionAdapter", endpoints);
            
            // Verificar si existen los directorios de datos
            File dataDir = new File("data/centro");
            if (!dataDir.exists()) {
                dataDir.mkdirs();
                logger.info("Directorio de datos creado: {}", dataDir.getAbsolutePath());
            }
            
            // Crear instancia de ValidadorDeVotos y agregarla al adaptador
            ValidadorDeVotosImpl validadorImpl = new ValidadorDeVotosImpl(votosRecibidosFile);
            Identity validadorId = new Identity(validadorServantName, "");
            adapter.add((ValidadorDeVotos) validadorImpl, validadorId);
            ValidadorDeVotosPrx validadorPrx = ValidadorDeVotosPrx.uncheckedCast(adapter.createProxy(validadorId));
            
            // Crear instancia de AlmacenamientoVotos y agregarla al adaptador
            AlmacenamientoVotos almacenamientoServant = new AlmacenamientoVotosImpl(votosRecibidosFile);
            Identity almacenamientoId = new Identity(almacenamientoServantName, "");
            adapter.add(almacenamientoServant, almacenamientoId);
            AlmacenamientoVotosPrx almacenamientoPrx = AlmacenamientoVotosPrx.uncheckedCast(adapter.createProxy(almacenamientoId));
            
            // Crear instancia de MotorEmisionVotos y agregarla al adaptador
            MotorEmisionVotosImpl motorEmisionServant = new MotorEmisionVotosImpl(candidatosFile, resultadosFile);
            Identity motorEmisionId = new Identity(motorEmisionServantName, "");
            adapter.add((MotorEmisionVotos) motorEmisionServant, motorEmisionId);
            MotorEmisionVotosPrx motorEmisionPrx = MotorEmisionVotosPrx.uncheckedCast(adapter.createProxy(motorEmisionId));
            
            // Crear instancia de GestorRecepcionVotos y agregarla al adaptador
            GestorRecepcionVotos gestorRecepcionServant = new GestorRecepcionVotosImpl(
                    almacenamientoPrx, validadorPrx, motorEmisionPrx, validadorImpl);
            Identity gestorRecepcionId = new Identity(gestorRecepcionServantName, "");
            adapter.add(gestorRecepcionServant, gestorRecepcionId);
            
            // Activar el adaptador
            adapter.activate();
            
            logger.info("Centro de Votación activado en: {}", endpoints);
            
            // Interfaz simple para mostrar resultados
            Thread uiThread = new Thread(() -> {
                Scanner scanner = new Scanner(System.in);
                
                while (true) {
                    System.out.println("\n=== CENTRO DE VOTACIÓN - MENÚ ===");
                    System.out.println("1. Mostrar resultados actuales");
                    System.out.println("2. Salir");
                    System.out.print("Seleccione una opción: ");
                    
                    String opcion = scanner.nextLine().trim();
                    
                    if (opcion.equals("1")) {
                        System.out.println("\n=== RESULTADOS ACTUALES ===");
                        System.out.println("CANDIDATO                    | VOTOS");
                        System.out.println("-----------------------------+-------");
                        
                    // En el método para mostrar resultados
                    motorEmisionServant.obtenerResultados().forEach((candidatoId, conteo) -> {
                        String nombre = motorEmisionServant.getNombreCandidato(candidatoId);
                        System.out.printf("%-28s | %5d%n", nombre, conteo);
                    });
                        System.out.println("-----------------------------+-------");
                        
                    } else if (opcion.equals("2")) {
                        logger.info("Cerrando Centro de Votación...");
                        communicator.shutdown();
                        break;
                    } else {
                        System.out.println("Opción no válida. Intente de nuevo.");
                    }
                }
                
                scanner.close();
            });
            
            uiThread.start();
            
            // Esperar a que se presione Ctrl+C o se cierre desde el menú
            communicator.waitForShutdown();
            
        } catch (Exception e) {
            logger.error("Error en el Centro de Votación", e);
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
        
        logger.info("Centro de Votación finalizado");
    }
}