package com.votacion.estacion;

import com.votacion.estacion.controller.ControllerEstacionImpl;
import com.votacion.estacion.monitoreo.SistemaMonitorizacionImpl;
import com.votacion.estacion.persistencia.AlmacenamientoTransitorioImpl;
import com.votacion.estacion.ui.VotacionConsoleUI;
import com.votacion.estacion.verificacion.VerificadorAsignacionImpl;
import com.votacion.estacion.votacion.GestorEnvioVotosImpl;

import VotacionSystem.*;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.Identity;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

/**
 * Clase principal que inicia la estación de votación.
 * Fecha: 2025-06-02
 * @author JRuiz1601
 */
public class EstacionVotacion {
    
    public static void main(String[] args) {
        int status = 0;
        Communicator communicator = null;
        
        try {
            // Inicializar el comunicador ICE
            communicator = Util.initialize(args);
            
            // Crear adaptador para objetos locales
            ObjectAdapter adapter = communicator.createObjectAdapter("EstacionAdapter");
            
            // Crear e inicializar componentes locales
            System.out.println("Inicializando componentes de la Estación de Votación...");
            
            // Componentes de verificación
            VerificadorAsignacionImpl verificador = new VerificadorAsignacionImpl();
            VerificadorAsignacionPrx verificadorPrx = VerificadorAsignacionPrx.uncheckedCast(
                    adapter.add(verificador, new Identity("verificador", "verificacion")));
            
            SistemaMonitorizacionImpl monitoreo = new SistemaMonitorizacionImpl(verificadorPrx);
            SistemaMonitorizacionPrx monitoreoPrx = SistemaMonitorizacionPrx.uncheckedCast(
                    adapter.add(monitoreo, new Identity("monitoreo", "monitorizacion")));
            
            ControllerEstacionImpl controller = new ControllerEstacionImpl(monitoreoPrx);
            ControllerEstacionPrx controllerPrx = ControllerEstacionPrx.uncheckedCast(
                    adapter.add(controller, new Identity("controller", "controlador")));
            
            // Componentes de persistencia
            AlmacenamientoTransitorioImpl almacenamiento = new AlmacenamientoTransitorioImpl();
            AlmacenamientoTransitorioPrx almacenamientoPrx = AlmacenamientoTransitorioPrx.uncheckedCast(
                    adapter.add(almacenamiento, new Identity("almacenamiento", "persistencia")));
            
            // Obtener proxy al gestor de recepción de votos en el centro
            GestorRecepcionVotosPrx gestorRecepcionPrx = GestorRecepcionVotosPrx.uncheckedCast(
                    communicator.stringToProxy("GestorRecepcion:default -p 10000"));
            
            // Gestor de envío de votos
            GestorEnvioVotosImpl gestorEnvio = new GestorEnvioVotosImpl(almacenamientoPrx, gestorRecepcionPrx);
            
            // Activar el adaptador
            adapter.activate();
            
            // Iniciar la interfaz de usuario
            VotacionConsoleUI ui = new VotacionConsoleUI(controllerPrx, gestorEnvio);
            
            System.out.println("Estación de Votación inicializada. Iniciando interfaz de usuario...");
            ui.iniciar();
            
        } catch (Exception e) {
            System.err.println("Error al iniciar la Estación de Votación:");
            e.printStackTrace();
            status = 1;
        } finally {
            if (communicator != null) {
                try {
                    communicator.destroy();
                } catch (Exception e) {
                    System.err.println("Error al destruir el comunicador:");
                    e.printStackTrace();
                    status = 1;
                }
            }
        }
        
        System.exit(status);
    }
}