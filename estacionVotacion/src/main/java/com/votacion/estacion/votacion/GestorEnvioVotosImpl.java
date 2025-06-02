package com.votacion.estacion.votacion;

import VotacionSystem.AlmacenamientoTransitorio;
import VotacionSystem.EstadoVoto;
import VotacionSystem.ErrorPersistenciaException;
import VotacionSystem.GestorEnvioVotosCallbackDisp;
import VotacionSystem.GestorRecepcionVotos;
import VotacionSystem.VotoDuplicadoException;
import VotacionSystem.Voto;
import VotacionSystem.VotoSeq;

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

    private final AlmacenamientoTransitorio almacenamientoTransitorio;
    private final GestorRecepcionVotos gestorRecepcionVotos;
    private final String estacionId = "MESA-01";
    private final ScheduledExecutorService scheduler;
    private boolean simulacionFalloRed = false;
    
    /**
     * Constructor del gestor de envío de votos.
     * @param almacenamientoTransitorio Almacenamiento transitorio
     * @param gestorRecepcionVotos Gestor de recepción de votos (remoto)
     */
    public GestorEnvioVotosImpl(AlmacenamientoTransitorio almacenamientoTransitorio, 
                               GestorRecepcionVotos gestorRecepcionVotos) {
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
            if (!simulacionFalloRed) {
                reintentarVotosPendientes();
            }
        }, 30, 30, TimeUnit.SECONDS);
    }
    
    /**
     * Envía un voto al centro de votación.
     * @param candidatoId ID del candidato elegido
     * @return ID único del voto
     */
    @Override
    public String enviarVoto(String candidatoId) {
        // Generar un ID único para el voto
        String votoId = UUID.randomUUID().toString();
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        
        System.out.println("Enviando voto ID: " + votoId + " para candidato ID: " + candidatoId);
        
        try {
            // Almacenar el voto localmente primero
            almacenamientoTransitorio.almacenarVotoTransitorio(votoId, candidatoId, EstadoVoto.PENDIENTE);
            
            // Si no hay simulación de fallo de red, enviar el voto
            if (!simulacionFalloRed) {
                enviarVotoAlCentro(votoId, candidatoId, timestamp);
            } else {
                System.out.println("Simulación de fallo de red activa. El voto se almacenó localmente y será enviado cuando se restablezca la conexión.");
            }
            
            return votoId;
        } catch (ErrorPersistenciaException e) {
            System.err.println("Error al almacenar voto: " + e.mensaje);
            throw new RuntimeException("Error al almacenar el voto localmente: " + e.mensaje);
        } catch (Exception e) {
            System.err.println("Error al enviar voto: " + e.getMessage());
            throw new RuntimeException("Error al enviar el voto: " + e.getMessage());
        }
    }
    
    /**
     * Envía un voto específico al centro de votación.
     * @param votoId ID del voto
     * @param candidatoId ID del candidato
     * @param timestamp Marca de tiempo
     */
    private void enviarVotoAlCentro(String votoId, String candidatoId, String timestamp) {
        try {
            // Crear objeto Voto para enviar
            Voto voto = new Voto();
            voto.votoId = votoId;
            voto.candidatoId = candidatoId;
            voto.estacionOrigen = estacionId;
            voto.timestamp = timestamp;
            
            // Crear callback para recibir confirmación
            GestorEnvioVotosCallbackImpl callback = new GestorEnvioVotosCallbackImpl(this, votoId);
            
            // Enviar voto al centro de votación
            gestorRecepcionVotos.recibirVoto(voto, callback);
            
            System.out.println("Voto enviado exitosamente al centro de votación: " + votoId);
        } catch (VotoDuplicadoException e) {
            System.err.println("Voto duplicado: " + e.mensaje);
            // Si el voto ya fue procesado, lo marcamos como confirmado
            try {
                almacenamientoTransitorio.marcarVotoConfirmado(votoId);
            } catch (ErrorPersistenciaException ex) {
                System.err.println("Error al marcar voto como confirmado: " + ex.mensaje);
            }
        } catch (ErrorPersistenciaException e) {
            System.err.println("Error de persistencia en el centro: " + e.mensaje);
        } catch (Exception e) {
            System.err.println("Error al enviar voto al centro: " + e.getMessage());
        }
    }
    
    /**
     * Procesa la confirmación de recepción de un voto.
     * @param votoId ID del voto
     * @param estado Estado del voto
     */
    public void procesarConfirmacionVoto(String votoId, EstadoVoto estado) {
        System.out.println("Recibida confirmación para voto: " + votoId + " con estado: " + estado);
        
        try {
            if (estado == EstadoVoto.PROCESADO || estado == EstadoVoto.RECIBIDO) {
                // Marcar el voto como confirmado en almacenamiento local
                almacenamientoTransitorio.marcarVotoConfirmado(votoId);
                System.out.println("Voto " + votoId + " marcado como confirmado");
            }
        } catch (ErrorPersistenciaException e) {
            System.err.println("Error al marcar voto como confirmado: " + e.mensaje);
        }
    }
    
    /**
     * Reintenta el envío de votos pendientes.
     */
    public void reintentarVotosPendientes() {
        System.out.println("Reintentando envío de votos pendientes...");
        
        try {
            VotoSeq votosPendientes = almacenamientoTransitorio.recuperarVotosPendientes();
            
            if (votosPendientes.length == 0) {
                System.out.println("No hay votos pendientes para reenviar");
                return;
            }
            
            System.out.println("Encontrados " + votosPendientes.length + " votos pendientes para reenviar");
            
            for (Voto voto : votosPendientes) {
                try {
                    System.out.println("Reintentando envío del voto: " + voto.votoId);
                    
                    // Crear callback para recibir confirmación
                    GestorEnvioVotosCallbackImpl callback = new GestorEnvioVotosCallbackImpl(this, voto.votoId);
                    
                    // Reenviar voto al centro de votación
                    gestorRecepcionVotos.recibirVoto(voto, callback);
                    
                    System.out.println("Voto reenviado exitosamente: " + voto.votoId);
                } catch (VotoDuplicadoException e) {
                    System.out.println("Voto duplicado durante reintento: " + e.mensaje);
                    // Si el voto ya fue procesado, lo marcamos como confirmado
                    almacenamientoTransitorio.marcarVotoConfirmado(voto.votoId);
                } catch (Exception e) {
                    System.err.println("Error al reintentar envío del voto: " + voto.votoId + " - " + e.getMessage());
                }
            }
        } catch (ErrorPersistenciaException e) {
            System.err.println("Error al recuperar votos pendientes: " + e.mensaje);
        } catch (Exception e) {
            System.err.println("Error en el proceso de reintento: " + e.getMessage());
        }
    }
    
    /**
     * Establece el estado de simulación de fallo de red.
     * @param simulacion true para activar la simulación de fallo
     */
    public void setSimulacionFalloRed(boolean simulacion) {
        this.simulacionFalloRed = simulacion;
        
        if (!simulacion) {
            // Si se desactiva la simulación, intentar enviar los votos pendientes
            reintentarVotosPendientes();
        }
    }
}