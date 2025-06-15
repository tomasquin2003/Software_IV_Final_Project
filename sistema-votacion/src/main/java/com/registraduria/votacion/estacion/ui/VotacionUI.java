package com.registraduria.votacion.estacion.ui;

import Votacion.ControllerEstacionPrx;
import Votacion.VotanteNoAutorizadoException;
import com.registraduria.votacion.estacion.votacion.GestorEnvioVotosImpl;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Interfaz de usuario basada en consola para el sistema de votación.
 * Permite a los votantes autenticarse y emitir sus votos.
 */
public class VotacionUI {
    private static final Logger logger = LoggerFactory.getLogger(VotacionUI.class);
    
    // Proxies para acceder a los servicios necesarios
    private final ControllerEstacionPrx controllerEstacion;
    private final GestorEnvioVotosImpl gestorEnvioVotos;
    
    // Scanner para leer entrada del usuario
    private final Scanner scanner = new Scanner(System.in);
    
    // Lista de candidatos
    private final List<Candidato> candidatos = new ArrayList<>();
    
    /**
     * Constructor que inicializa la UI con las dependencias necesarias.
     * 
     * @param controllerEstacion Proxy para el controlador de la estación
     * @param gestorEnvioVotos Implementación del gestor de envío de votos
     * @param rutaArchivoCandidatos Ruta al archivo CSV de candidatos
     */
    public VotacionUI(
            ControllerEstacionPrx controllerEstacion,
            GestorEnvioVotosImpl gestorEnvioVotos,
            String rutaArchivoCandidatos) {
        
        this.controllerEstacion = controllerEstacion;
        this.gestorEnvioVotos = gestorEnvioVotos;
        
        // Cargar lista de candidatos
        cargarCandidatos(rutaArchivoCandidatos);
        
        logger.info("VotacionUI inicializada con {} candidatos", candidatos.size());
    }
    
    /**
     * Carga la lista de candidatos desde un archivo CSV.
     * 
     * @param rutaArchivoCandidatos Ruta al archivo CSV de candidatos
     */
    private void cargarCandidatos(String rutaArchivoCandidatos) {
        logger.info("Cargando candidatos desde: {}", rutaArchivoCandidatos);
        
        try (FileReader reader = new FileReader(rutaArchivoCandidatos, StandardCharsets.UTF_8);
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT
                     .withFirstRecordAsHeader()
                     .withIgnoreHeaderCase()
                     .withTrim())) {
            
            for (CSVRecord csvRecord : csvParser) {
                String id = csvRecord.get("id");
                String nombre = csvRecord.get("nombre");
                String partido = csvRecord.get("partido");
                
                candidatos.add(new Candidato(id, nombre, partido));
                logger.debug("Candidato cargado: {}, {}, {}", id, nombre, partido);
            }
            
            logger.info("Se cargaron {} candidatos", candidatos.size());
            
        } catch (IOException e) {
            logger.error("Error al cargar el archivo de candidatos", e);
            throw new RuntimeException("No se pudo cargar el archivo de candidatos: " + e.getMessage(), e);
        }
    }
    
    /**
     * Inicia la interfaz de usuario y el flujo de votación.
     */
    public void iniciar() {
        logger.info("Iniciando interfaz de usuario");
        
        mostrarEncabezado();
        
        while (true) {
            try {
                // Solicitar cédula del votante
                String cedula = solicitarCedula();
                
                if (cedula.equalsIgnoreCase("salir")) {
                    logger.info("Usuario solicitó salir del sistema");
                    break;
                }
                
                // Autenticar votante
                if (autenticarVotante(cedula)) {
                    // Mostrar candidatos y solicitar voto
                    String candidatoId = solicitarVoto();
                      if (candidatoId != null) {
                        // CAMBIO CRÍTICO: Enviar voto con la cédula del votante
                        String votoId = gestorEnvioVotos.enviarVoto(candidatoId, cedula);
                        mostrarConfirmacion(votoId);
                    }
                }
                
                // Pequeña pausa antes de reiniciar
                System.out.println("\nPresione Enter para continuar...");
                scanner.nextLine();
                
                limpiarPantalla();
                mostrarEncabezado();
                
            } catch (Exception e) {
                logger.error("Error en el flujo de votación", e);
                System.out.println("\n¡ERROR! Se produjo un problema inesperado.");
                System.out.println("Por favor, contacte al administrador de la mesa.");
                
                // Pequeña pausa antes de reiniciar
                System.out.println("\nPresione Enter para reiniciar...");
                scanner.nextLine();
                
                limpiarPantalla();
                mostrarEncabezado();
            }
        }
        
        // Cerrar scanner al finalizar
        scanner.close();
        logger.info("Interfaz de usuario finalizada");
    }
    
    /**
     * Muestra el encabezado de la aplicación.
     */
    private void mostrarEncabezado() {
        System.out.println("=====================================================");
        System.out.println("           SISTEMA DE VOTACION ELECTRONICA           ");
        System.out.println("=====================================================");
        System.out.println();
    }
    
    /**
     * Solicita y valida la cédula del votante.
     * 
     * @return Cédula ingresada por el usuario
     */
    private String solicitarCedula() {
        System.out.println("Ingrese el numero de cedula del votante (o 'salir' para terminar):");
        String cedula = scanner.nextLine().trim();
        
        // Validar formato de cédula (simplificado para el ejemplo)
        while (!cedula.equalsIgnoreCase("salir") && !cedula.matches("\\d+")) {
            System.out.println("Formato de cedula invalido. Por favor, ingrese solo numeros:");
            cedula = scanner.nextLine().trim();
        }
        
        return cedula;
    }
    
    /**
     * Autentica al votante utilizando el ControllerEstacion.
     * 
     * @param cedula Cédula del votante a autenticar
     * @return true si la autenticación fue exitosa
     */
    private boolean autenticarVotante(String cedula) {
        System.out.println("\nAutenticando votante...");
        
        try {
            boolean resultado = controllerEstacion.autenticarVotante(cedula);
            
            if (resultado) {
                System.out.println("¡Autenticion exitosa! El votante puede proceder a votar.");
                return true;
            } else {
                System.out.println("Autenticacion fallida. El votante no está autorizado.");
                return false;
            }
            
        } catch (VotanteNoAutorizadoException e) {
            System.out.println("Error de autenticación: " + e.mensaje);
            logger.warn("Error de autenticación para cédula {}: {}", cedula, e.mensaje);
            return false;
        } catch (Exception e) {
            System.out.println("Error del sistema durante la autenticación.");
            logger.error("Error del sistema durante la autenticación", e);
            return false;
        }
    }
    
    /**
     * Muestra la lista de candidatos y solicita al votante que elija uno.
     * 
     * @return ID del candidato elegido, o null si se canceló
     */
    private String solicitarVoto() {
        System.out.println("\n----- CANDIDATOS DISPONIBLES -----");
        
        // Mostrar lista de candidatos
        for (Candidato candidato : candidatos) {
            System.out.printf("%s. %s (%s)%n", candidato.getId(), candidato.getNombre(), candidato.getPartido());
        }
        
        System.out.println("\nIngrese el numero del candidato de su eleccion (o 'cancelar'):");
        String seleccion = scanner.nextLine().trim();
        
        if (seleccion.equalsIgnoreCase("cancelar")) {
            System.out.println("Votación cancelada por el usuario.");
            return null;
        }
        
        // Validar selección
        for (Candidato candidato : candidatos) {
            if (candidato.getId().equals(seleccion)) {
                System.out.printf("Ha seleccionado a: %s (%s)%n", candidato.getNombre(), candidato.getPartido());
                
                // Solicitar confirmación
                System.out.println("\n¿Confirma su voto? (s/n):");
                String confirmacion = scanner.nextLine().trim().toLowerCase();
                
                if (confirmacion.equals("s") || confirmacion.equals("si")) {
                    return candidato.getId();
                } else {
                    System.out.println("Voto cancelado. Por favor, seleccione nuevamente.");
                    return solicitarVoto(); // Recursión para volver a seleccionar
                }
            }
        }
        
        // Si llegamos aquí, la selección no fue válida
        System.out.println("Selección inválida. Por favor, ingrese un número de candidato válido.");
        return solicitarVoto(); // Recursión para volver a seleccionar
    }
    
    /**
     * Muestra la confirmación de que el voto fue registrado.
     * 
     * @param votoId ID del voto registrado
     */
    private void mostrarConfirmacion(String votoId) {
        System.out.println("\n¡VOTO REGISTRADO EXITOSAMENTE!");
        System.out.println("ID del voto: " + votoId);
        System.out.println("Fecha y hora: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        System.out.println("\nGracias por participar en las elecciones.");
    }
    
    /**
     * Limpia la pantalla de la consola (intento multiplataforma).
     */
    private void limpiarPantalla() {
        try {
            final String os = System.getProperty("os.name");
            
            if (os.contains("Windows")) {
                // Para Windows
                new ProcessBuilder("cmd", "/c", "cls").inheritIO().start().waitFor();
            } else {
                // Para Unix/Linux/MacOS
                System.out.print("\033[H\033[2J");
                System.out.flush();
            }
        } catch (Exception e) {
            // Si falla, imprimir líneas en blanco
            for (int i = 0; i < 50; i++) {
                System.out.println();
            }
        }
    }
    
    /**
     * Clase interna para representar un candidato.
     */
    private static class Candidato {
        private final String id;
        private final String nombre;
        private final String partido;
        
        public Candidato(String id, String nombre, String partido) {
            this.id = id;
            this.nombre = nombre;
            this.partido = partido;
        }
        
        public String getId() {
            return id;
        }
        
        public String getNombre() {
            return nombre;
        }
        
        public String getPartido() {
            return partido;
        }
    }
}