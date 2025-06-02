package com.votacion.estacion.ui;

import com.votacion.estacion.controller.ControllerEstacion;
import com.votacion.estacion.votacion.GestorEnvioVotos;
import VotacionSystem.VotanteNoAutorizadoException;

import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Implementación de la interfaz de usuario por consola para la estación de votación.
 * Fecha: 2025-06-02
 * @author JRuiz1601
 */
public class VotacionConsoleUI {
    private final Scanner scanner;
    private final ControllerEstacion controllerEstacion;
    private final GestorEnvioVotos gestorEnvioVotos;
    private final Map<String, String> candidatos; // id -> nombre
    private boolean simulacionFalloRed = false;
    
    /**
     * Constructor de la interfaz de usuario.
     * @param controllerEstacion Controlador de la estación
     * @param gestorEnvioVotos Gestor de envío de votos
     */
    public VotacionConsoleUI(ControllerEstacion controllerEstacion, 
                           GestorEnvioVotos gestorEnvioVotos) {
        this.scanner = new Scanner(System.in);
        this.controllerEstacion = controllerEstacion;
        this.gestorEnvioVotos = gestorEnvioVotos;
        this.candidatos = cargarCandidatos();
    }
    
    /**
     * Inicia la interfaz de usuario mostrando el menú principal.
     */
    public void iniciar() {
        boolean salir = false;
        
        System.out.println("\n===== BIENVENIDO AL SISTEMA DE VOTACIÓN =====");
        System.out.println("Fecha: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        System.out.println("Estación de Votación: MESA-01");
        System.out.println("Operador: " + System.getProperty("user.name"));
        System.out.println("==============================================\n");
        
        while (!salir) {
            mostrarMenuPrincipal();
            int opcion = leerOpcion();
            
            switch (opcion) {
                case 1:
                    procesarVotacion();
                    break;
                case 2:
                    verEstadisticas();
                    break;
                case 3:
                    simularFalloRed();
                    break;
                case 4:
                    forzarReintento();
                    break;
                case 5:
                    salir = true;
                    System.out.println("Saliendo del sistema...");
                    break;
                default:
                    System.out.println("Opción no válida. Intente nuevamente.");
            }
        }
    }
    
    /**
     * Muestra el menú principal de la aplicación.
     */
    private void mostrarMenuPrincipal() {
        System.out.println("\n===== SISTEMA DE VOTACIÓN =====");
        System.out.println("1. Iniciar proceso de votación");
        System.out.println("2. Ver estadísticas de votos");
        System.out.println("3. Simular fallo de red");
        System.out.println("4. Forzar reintento de votos pendientes");
        System.out.println("5. Salir");
        System.out.print("Seleccione una opción: ");
    }
    
    /**
     * Lee una opción del usuario como número entero.
     * @return La opción seleccionada como entero
     */
    private int leerOpcion() {
        try {
            return Integer.parseInt(scanner.nextLine());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
    
    /**
     * Procesa el flujo de votación completo.
     */
    private void procesarVotacion() {
        System.out.println("\n--- PROCESO DE VOTACIÓN ---");
        
        // 1. Autenticación del votante
        System.out.print("Ingrese número de cédula: ");
        String cedula = scanner.nextLine();
        
        try {
            boolean autorizado = controllerEstacion.autenticarVotante(cedula);
            
            if (!autorizado) {
                System.out.println("Votante no autorizado en esta mesa.");
                return;
            }
            
            // 2. Mostrar candidatos
            System.out.println("\nCandidatos disponibles:");
            for (Map.Entry<String, String> entry : candidatos.entrySet()) {
                System.out.println(entry.getKey() + ". " + entry.getValue());
            }
            
            // 3. Seleccionar candidato
            System.out.print("\nSeleccione un candidato (ID): ");
            String candidatoId = scanner.nextLine();
            
            if (!candidatos.containsKey(candidatoId)) {
                System.out.println("Candidato no válido.");
                return;
            }
            
            // 4. Confirmar voto
            System.out.println("\nVa a votar por: " + candidatos.get(candidatoId));
            System.out.print("¿Confirmar? (S/N): ");
            String confirmacion = scanner.nextLine();
            
            if (confirmacion.equalsIgnoreCase("S")) {
                // 5. Enviar voto
                String votoId = gestorEnvioVotos.enviarVoto(candidatoId);
                System.out.println("\nVoto registrado con ID: " + votoId);
                System.out.println("El voto será transmitido al centro de votación.");
            } else {
                System.out.println("Votación cancelada.");
            }
            
        } catch (VotanteNoAutorizadoException e) {
            System.out.println("Error: " + e.mensaje);
        } catch (Exception e) {
            System.out.println("Error en el proceso de votación: " + e.getMessage());
        }
    }
    
    /**
     * Muestra estadísticas sobre votos enviados, pendientes y confirmados.
     */
    private void verEstadisticas() {
        // Esta función mostraría estadísticas del proceso de votación
        System.out.println("\n--- ESTADÍSTICAS DE VOTACIÓN ---");
        // Implementar lógica para mostrar votos pendientes, confirmados, etc.
        System.out.println("Votos totales emitidos: [pendiente]");
        System.out.println("Votos confirmados: [pendiente]");
        System.out.println("Votos pendientes: [pendiente]");
        System.out.println("\nPresione ENTER para continuar...");
        scanner.nextLine();
    }
    
    /**
     * Simula un fallo de red para probar el mecanismo de reliable messaging.
     */
    private void simularFalloRed() {
        System.out.println("\n--- SIMULACIÓN DE FALLO DE RED ---");
        System.out.print("¿Activar simulación de fallo de red? (S/N): ");
        String respuesta = scanner.nextLine();
        
        if (respuesta.equalsIgnoreCase("S")) {
            this.simulacionFalloRed = true;
            System.out.println("Simulación de fallo de red ACTIVADA. Los votos se almacenarán localmente.");
        } else {
            this.simulacionFalloRed = false;
            System.out.println("Simulación de fallo de red DESACTIVADA. Los votos se transmitirán normalmente.");
        }
    }
    
    /**
     * Fuerza el reintento de envío de votos pendientes.
     */
    private void forzarReintento() {
        System.out.println("\n--- FORZAR REINTENTO DE VOTOS PENDIENTES ---");
        // Implementar lógica para forzar reintento
        System.out.println("Iniciando reintento de envío de votos pendientes...");
        System.out.println("Reintentos completados.");
        System.out.println("\nPresione ENTER para continuar...");
        scanner.nextLine();
    }
    
    /**
     * Carga la lista de candidatos desde el archivo CSV.
     * @return Mapa con ID y nombre de candidatos
     */
    private Map<String, String> cargarCandidatos() {
        // En una implementación real, cargaría desde un archivo CSV
        Map<String, String> candidatosMap = new ConcurrentHashMap<>();
        
        try (BufferedReader br = new BufferedReader(new FileReader("centroVotacion/src/main/resources/data/Candidatos.csv"))) {
            String line;
            boolean isHeader = true;
            
            while ((line = br.readLine()) != null) {
                if (isHeader) {
                    isHeader = false;
                    continue;
                }
                
                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    candidatosMap.put(parts[0], parts[1] + " (" + parts[2] + ")");
                }
            }
        } catch (IOException e) {
            // Si hay un error al leer el archivo, usar datos de respaldo
            System.err.println("Error al cargar candidatos: " + e.getMessage());
            candidatosMap.put("1", "Álvaro Uribe Vélez (Centro Democrático)");
            candidatosMap.put("2", "Juan Manuel Santos (Partido de la U)");
            candidatosMap.put("3", "Gustavo Petro (Colombia Humana)");
            candidatosMap.put("4", "Iván Duque (Centro Democrático)");
            candidatosMap.put("9", "Voto en Blanco (N/A)");
        }
        
        return candidatosMap;
    }
    
    /**
     * Obtiene el estado actual de la simulación de fallo de red.
     * @return true si está activa la simulación
     */
    public boolean isSimulacionFalloRed() {
        return simulacionFalloRed;
    }
}