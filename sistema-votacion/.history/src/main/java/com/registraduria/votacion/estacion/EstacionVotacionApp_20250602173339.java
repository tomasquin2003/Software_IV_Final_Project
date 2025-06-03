package com.registraduria.votacion.estacion;

import Votacion.AlmacenamientoTransitorio;
import Votacion.AlmacenamientoTransitorioPrx;
import Votacion.ControllerEstacion;
import Votacion.ControllerEstacionPrx;
import Votacion.GestorEnvioVotosCallback;
import Votacion.GestorRecepcionVotosPrx;
import Votacion.SistemaMonitorizacion;
import Votacion.SistemaMonitorizacionPrx;
import Votacion.VerificadorAsignacion;
import Votacion.VerificadorAsignacionPrx;

import com.registraduria.votacion.estacion.controller.ControllerEstacionImpl;
import com.registraduria.votacion.estacion.monitoreo.SistemaMonitorizacionImpl;
import com.registraduria.votacion.estacion.persistencia.AlmacenamientoTransitorioImpl;
import com.registraduria.votacion.estacion.ui.VotacionUI;
import com.registraduria.votacion.estacion.verificacion.VerificadorAsignacionImpl;
import com.registraduria.votacion.estacion.votacion.GestorEnvioVotosImpl;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Identity;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;

/**
 * Aplicación principal para la Estación de Votación.
 */
public class EstacionVotacionApp {
    private static final Logger logger = LoggerFactory.getLogger(EstacionVotacionApp.class);
    
    /**
     * Método principal que inicia la aplicación.
     * 
     * @param args Argumentos de línea de comandos
     */
    public static void main(String[] args) {
        logger.info("Iniciando Estación de Votación");
        
        // ID de la estación (podría venir de configuración)
        String estacionId = "Estacion01";
        
        // Ruta del archivo de configuración
        String configFile = "src/main/resources/config/estacion.config";
        
        // Procesar argumentos de línea de comandos
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--config") && i + 1 < args.length) {
                configFile = args[i + 1];
                i++;
            } else if (args[i].equals("--id") && i + 1 < args.length) {
                estacionId = args[i + 1];
                i++;
            }
        }
        
        logger.info("Usando archivo de configuración: {}", configFile);
        logger.info("ID de la Estación: {}", estacionId);
        
        // Crear instancia de Ice Communicator
        try (Communicator communicator = Util.initialize(args)) {
            
            // Cargar propiedades desde el archivo de configuración
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(configFile)) {
                props.load(fis);
            }
            
            // Obtener endpoints y nombres de los servants desde la configuración
            String endpoints = props.getProperty("EstacionVotacion.Endpoints", "tcp -h localhost -p 10000");
            String controllerServantName = props.getProperty("EstacionVotacion.ControllerEstacionServant", "ControllerEstacion");
            String monitorServantName = props.getProperty("EstacionVotacion.SistemaMonitorizacionServant", "SistemaMonitorizacion");
            String verificadorServantName = props.getProperty("EstacionVotacion.VerificadorAsignacionServant", "VerificadorAsignacion");
            String almacenamientoServantName = props.getProperty("EstacionVotacion.AlmacenamientoTransitorioServant", "AlmacenamientoTransitorio");
            String gestorEnvioServantName = props.getProperty("EstacionVotacion.GestorEnvioVotosServant", "GestorEnvioVotos");
            
            // Rutas de archivos de datos
            String cedulasFile = props.getProperty("EstacionVotacion.CedulasAutorizadasFile", "src/main/resources/data/CedulasAutorizadas.csv");
            String votosTransitoriosFile = props.getProperty("EstacionVotacion.VotosTransitoriosFile", "data/estacion/VotosTransitorios.csv");
            String candidatosFile = props.getProperty("CentroVotacion.CandidatosFile", "src/main/resources/data/Candidatos.csv");
            
            // Proxy para el Centro de Votación
            String centroProxy = props.getProperty("CentroVotacion.Proxy", "GestorRecepcionVotos:tcp -h localhost -p 10001");
            
            // Crear adaptador para la Estación de Votación
            ObjectAdapter adapter = communicator.createObjectAdapterWithEndpoints("EstacionVotacionAdapter", endpoints);
            
            // Crear instancia de VerificadorAsignacion y agregarla al adaptador
            VerificadorAsignacion verificadorServant = new VerificadorAsignacionImpl(cedulasFile);
            Identity verificadorId = new Identity(verificadorServantName, "");
            adapter.add(verificadorServant, verificadorId);
            VerificadorAsignacionPrx verificadorPrx = VerificadorAsignacionPrx.uncheckedCast(adapter.createProxy(verificadorId));
            
            // Crear instancia de SistemaMonitorizacion y agregarla al adaptador
            SistemaMonitorizacion monitorServant = new SistemaMonitorizacionImpl(verificadorPrx);
            Identity monitorId = new Identity(monitorServantName, "");
            adapter.add(monitorServant, monitorId);
            SistemaMonitorizacionPrx monitorPrx = SistemaMonitorizacionPrx.uncheckedCast(adapter.createProxy(monitorId));
            
            // Crear instancia de ControllerEstacion y agregarla al adaptador
            ControllerEstacion controllerServant = new ControllerEstacionImpl(monitorPrx);
            Identity controllerId = new Identity(controllerServantName, "");
            adapter.add(controllerServant, controllerId);
            ControllerEstacionPrx controllerPrx = ControllerEstacionPrx.uncheckedCast(adapter.createProxy(controllerId));
            
            // Crear instancia de AlmacenamientoTransitorio y agregarla al adaptador
            AlmacenamientoTransitorio almacenamientoServant = new AlmacenamientoTransitorioImpl(votosTransitoriosFile, estacionId);
            Identity almacenamientoId = new Identity(almacenamientoServantName, "");
            adapter.add(almacenamientoServant, almacenamientoId);
            AlmacenamientoTransitorioPrx almacenamientoPrx = AlmacenamientoTransitorioPrx.uncheckedCast(adapter.createProxy(almacenamientoId));
            
            // Obtener proxy para el Centro de Votación
            GestorRecepcionVotosPrx centroVotacionPrx = GestorRecepcionVotosPrx.checkedCast(
                    communicator.stringToProxy(centroProxy));
            
            if (centroVotacionPrx == null) {
                throw new RuntimeException("Proxy inválido para el Centro de Votación");
            }
            
            // Crear instancia de GestorEnvioVotos
            GestorEnvioVotosImpl gestorEnvioServant = new GestorEnvioVotosImpl(
                    almacenamientoPrx, centroVotacionPrx, estacionId);

            // Registrar el servant (debe implementar GestorEnvioVotosCallback)
            Identity gestorEnvioId = new Identity(gestorEnvioServantName, "");
            adapter.add((GestorEnvioVotosCallback) gestorEnvioServant, gestorEnvioId);
            
            // Activar el adaptador
            adapter.activate();
            
            logger.info("Adaptador de Estación de Votación activado en: {}", endpoints);
            
            // Verificar si existen los directorios de datos
            File dataDir = new File("data/estacion");
            if (!dataDir.exists()) {
                dataDir.mkdirs();
                logger.info("Directorio de datos creado: {}", dataDir.getAbsolutePath());
            }
            
            // Crear la interfaz de usuario
            VotacionUI ui = new VotacionUI(controllerPrx, gestorEnvioServant, candidatosFile);
            
            // Iniciar la interfaz de usuario
            logger.info("Iniciando interfaz de usuario");
            ui.iniciar();
            
            // Esperar a que se presione Ctrl+C para finalizar
            logger.info("Esperando señal de finalización...");
            communicator.waitForShutdown();
            
        } catch (Exception e) {
            logger.error("Error en la Estación de Votación", e);
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
        
        logger.info("Estación de Votación finalizada");
    }
}