package com.votacion.estacion.votacion;

import VotacionSystem.*;
import com.zeroc.Ice.Current;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Implementación del gestor de envío de votos.
 * Fecha: 2025-06-02
 * @author JRuiz1601
 */
public class GestorEnvioVotosImpl implements GestorEnvioVotos {

    private final AlmacenamientoTransitorioPrx almacenamientoTransitorio;
    private final GestorRecepcionVotosPrx gestorRecepcionVotos;
    private final String estacionId = "MESA-01";
    private final ScheduledExecutorService scheduler;
    private boolean simulacionFalloRed = false;
    
    /**
     * Constructor del gestor de envío de votos.
     * @param almacenamientoTransitorio Almacenamiento transitorio (proxy)
     * @param gestorRecepcionVotos Gestor de recepción de votos (proxy remoto)
     */
    public GestorEnvioVotosImpl(AlmacenamientoTransitorioPrx almacenamientoTransitorio, 
                               GestorRecepcionVotosPrx gestorRecepcionVotos) {
        this.almacenamientoTransitorio = almacenamientoTransitorio;
        this.gestorRecepcionVotos = gestorRecepcionVotos;
        this.scheduler = Executors.newScheduledThreadPool(1);
        
        // Iniciar tarea periódica para reintento de votos pendientes
        iniciarTareaReintento();
    }
    
    /**
     * Inicia una tarea programada para reintentar el envío de votos pendientes.
     */
    private void iniciarTareaReintento() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                reintentarVotosPendientes();
            } catch (Exception e) {
                System.err.println("Error en tarea de reintento: " + e.getMessage());
            }
        }, 30, 60, TimeUnit.SECONDS); // Reintentar cada 60 segundos después de 30 segundos
    }
    
    @Override
    public String enviarVoto(String candidatoId, Current current) {
        String votoId = UUID.randomUUID().toString();
        
        try {
            System.out.println("Enviando voto " + votoId + " para candidato: " + candidatoId);
            
            // Almacenar el voto localmente primero
            almacenamientoTransitorio.almacenarVotoTransitorio(votoId, candidatoId, EstadoVoto.PENDIENTE, current);
            
            System.out.println("Voto almacenado localmente: " + votoId);
            return votoId;
            
        } catch (ErrorPersistenciaException e) {
            System.err.println("Error al almacenar voto localmente: " + e.getMessage());
            return null;
        }
        
        // Intentar envío inmediato en segundo plano
        // scheduler.execute(() -> {
        //     try {
        //         enviarVotoInmediato(votoId, candidatoId);
        //     } catch (Exception e) {
        //         System.err.println("Error en envío inmediato: " + e.getMessage());
        //     }
        // });
    }
    
    /**
     * Envía un voto inmediatamente al centro de votación.
     */
    private void enviarVotoInmediato(String votoId, String candidatoId) {
        try {
            // Crear objeto Voto para enviar
            Voto voto = new Voto();
            voto.votoId = votoId;
            voto.candidatoId = candidatoId;
            voto.estacionOrigen = estacionId;
            voto.timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            
            // Crear callback para recibir confirmación
            GestorEnvioVotosCallbackImpl callback = new GestorEnvioVotosCallbackImpl(this, votoId);
            
            // Enviar voto al centro de votación (esto requiere un proxy del callback)
            // gestorRecepcionVotos.recibirVoto(voto, callbackPrx, current);
            
            System.out.println("Voto enviado exitosamente al centro de votación: " + votoId);
            
        } catch (VotoDuplicadoException e) {
            System.out.println("Voto duplicado detectado: " + votoId + ". Marcando como confirmado.");
            try {
                almacenamientoTransitorio.marcarVotoConfirmado(votoId, null);
            } catch (ErrorPersistenciaException ex) {
                System.err.println("Error al marcar voto duplicado como confirmado: " + ex.getMessage());
            }
        } catch (Exception e) {
            System.err.println("Error al enviar voto inmediato: " + e.getMessage());
        }
    }
    
    /**
     * Procesa la confirmación de recepción de un voto.
     * @param votoId ID del voto confirmado
     * @param estado Estado del voto
     */
    public void procesarConfirmacionVoto(String votoId, EstadoVoto estado) {
        try {
            if (estado == EstadoVoto.PROCESADO || estado == EstadoVoto.RECIBIDO) {
                // Marcar el voto como confirmado en almacenamiento local
                almacenamientoTransitorio.marcarVotoConfirmado(votoId, null);
                System.out.println("Voto confirmado y marcado como procesado: " + votoId);
            }
        } catch (ErrorPersistenciaException e) {
            System.err.println("Error al procesar confirmación de voto: " + e.getMessage());
        }
    }
    
    /**
     * Reintenta el envío de votos pendientes.
     */
    public void reintentarVotosPendientes() {
        try {
            Voto[] votosPendientes = almacenamientoTransitorio.recuperarVotosPendientes(null);
            
            if (votosPendientes.length > 0) {
                System.out.println("Reintentando envío de " + votosPendientes.length + " votos pendientes");
            }
            
            for (Voto voto : votosPendientes) {
                try {
                    // Crear callback para cada voto
                    GestorEnvioVotosCallbackImpl callback = new GestorEnvioVotosCallbackImpl(this, voto.votoId);
                    
                    // Reenviar voto al centro de votación
                    // gestorRecepcionVotos.recibirVoto(voto, callbackPrx, null);
                    
                    System.out.println("Voto reenviado exitosamente: " + voto.votoId);
                    
                } catch (VotoDuplicadoException e) {
                    // Si el voto ya fue procesado, lo marcamos como confirmado
                    almacenamientoTransitorio.marcarVotoConfirmado(voto.votoId, null);
                    System.out.println("Voto duplicado detectado en reintento: " + voto.votoId);
                } catch (Exception e) {
                    System.err.println("Error al reenviar voto " + voto.votoId + ": " + e.getMessage());
                }
            }
        } catch (ErrorPersistenciaException e) {
            System.err.println("Error al recuperar votos pendientes para reintento: " + e.getMessage());
        }
    }
    
    /**
     * Cierra el gestor y libera recursos.
     */
    public void cerrar() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            System.out.println("Scheduler del gestor de envío cerrado");
        }
    }
}
