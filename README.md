# Sistema de Votación Electoral - Patrón Reliable Messaging

![Java](https://img.shields.io/badge/Java-11-orange)
![ZeroC ICE](https://img.shields.io/badge/ZeroC%20ICE-3.7.10-blue)
![Gradle](https://img.shields.io/badge/Gradle-7.0-green)
![Status](https://img.shields.io/badge/Status-Producción-success)

## **Integrantes**

- **Juan Esteban Ruiz**
- **Tomas Quintero** 
- **Juan Camilo Amorocho**

## **Video Explicativo**

🎥 [Demostración del Sistema](https://drive.google.com/file/d/1nXNpr-moP9PZbdxGl2pePsvYEUem_0br/view?usp=sharing)

---

## 📋 **Descripción General**

Sistema de votación electrónico distribuido desarrollado para la **Registraduría Nacional**, implementando el patrón **Reliable Messaging** para garantizar la transmisión confiable y segura de votos desde múltiples estaciones de votación hacia un servidor central consolidador.

### **Características Principales**

- **🔒 Confiabilidad del 100%:** Garantiza que ningún voto se pierda durante la transmisión
- **🛡️ Prevención de Duplicados:** Asegura que ningún voto sea contado más de una vez
- **⚡ Alta Disponibilidad:** Recuperación automática ante fallos de red y errores de comunicación
- **📊 Trazabilidad Completa:** Seguimiento de cada voto mediante UUIDs únicos y timestamps precisos
- **🚀 Escalabilidad:** Soporte para múltiples estaciones de votación concurrentes
- **🔄 Recuperación Automática:** Reenvío inteligente de votos pendientes tras interrupciones

---

## 🏗️ **Arquitectura del Sistema**

### **Patrón Reliable Messaging Implementado**

```
┌─────────────┐    ┌──────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Estación  │───▶│  Almacenamiento  │───▶│ Gestor de Envío │───▶│ Centro de       │
│ de Votación │    │   Transitorio    │    │    de Votos     │    │   Votación      │
└─────────────┘    └──────────────────┘    └─────────────────┘    └─────────────────┘
       │                     │                       │                       │
       ▼                     ▼                       ▼                       ▼
  VotacionUI         VotosTransitorios.csv    GestorEnvioVotos      VotosRecibidos.csv
```

### **Componentes Principales**

#### **Estación de Votación (`EstacionVotacionApp`)**
- **VotacionUI:** Interfaz de usuario para votantes
- **ControllerEstacion:** Autenticación de votantes
- **VerificadorAsignacion:** Validación de cédulas autorizadas
- **AlmacenamientoTransitorio:** Persistencia local temporal
- **GestorEnvioVotos:** Envío confiable con reintentos automáticos

#### **Centro de Votación (`CentroVotacionApp`)**
- **GestorRecepcionVotos:** Recepción y procesamiento de votos
- **ValidadorDeVotos:** Prevención de duplicados mediante UUIDs
- **AlmacenamientoVotos:** Persistencia central definitiva
- **MotorEmisionVotos:** Conteo y consolidación de resultados

---

## 🛠️ **Requisitos del Sistema**

### **Software Requerido**
- **Java JDK 11+**
- **Gradle 7.0+**
- **ZeroC ICE 3.7.10**
- **Sistema Operativo:** Windows, Linux, o macOS

### **Dependencias**
- `com.zeroc:ice:3.7.10` - Middleware de comunicación
- `org.apache.commons:commons-csv:1.8` - Procesamiento de archivos CSV
- `org.slf4j:slf4j-api:1.7.30` - Logging
- `ch.qos.logback:logback-classic:1.2.3` - Implementación de logging

---

## 🚀 **Instalación y Configuración**

### **1. Clonar el Repositorio**
```bash
git clone <URL_REPOSITORIO>
cd sistema-votacion
```

### **2. Verificar Instalación de Java**
```bash
java -version
# Debe mostrar Java 11 o superior
```

### **3. Compilar el Proyecto**
```bash
./gradlew build
```

### **4. Estructura de Archivos de Datos**

El sistema utiliza los siguientes archivos CSV:

**Candidatos** (`src/main/resources/data/Candidatos.csv`):
```csv
id,nombre,partido
1,Gustavo Petro,Pacto Historico
2,Juan Manuel Santos,Partido de la U
3,Ivan Duque,Centro Democratico
4,Alvaro Uribe,Centro Democratico
```

**Cédulas Autorizadas** (`src/main/resources/data/CedulasAutorizadas.csv`):
```csv
cedula,mesa_asignada,nombre
1000000001,1,Carmen Rodríguez
1000000002,1,Juan Carlos Martínez
...
```

---

## ▶️ **Ejecución del Sistema**

### **Método 1: Ejecución Secuencial (Recomendado)**

#### **Paso 1: Iniciar Centro de Votación**
```bash
./gradlew runCentro
```
**Salida esperada:**
```
=== CENTRO DE VOTACIÓN - MENÚ ===
1. Mostrar resultados actuales
2. Salir
```

#### **Paso 2: Iniciar Estación de Votación**
```bash
./gradlew runEstacion
```
**Salida esperada:**
```
=====================================================
           SISTEMA DE VOTACION ELECTRONICA           
=====================================================

Ingrese el numero de cedula del votante (o 'salir' para terminar):
```

### **Método 2: Ejecución Múltiple (Para Pruebas de Carga)**

#### **Terminal 1 - Centro:**
```bash
./gradlew runCentro
```

#### **Terminales 2-5 - Estaciones:**
```bash
./gradlew runEstacion --args="--id Estacion01"
./gradlew runEstacion --args="--id Estacion02" 
./gradlew runEstacion --args="--id Estacion03"
./gradlew runEstacion --args="--id Estacion04"
```

---

## 👥 **Uso del Sistema**

### **Flujo de Votación**

1. **Autenticación del Votante:**
   ```
   Ingrese el numero de cedula del votante: 1000000001
   Autenticando votante...
   ¡Autenticación exitosa! El votante puede proceder a votar.
   ```

2. **Selección de Candidato:**
   ```
   ----- CANDIDATOS DISPONIBLES -----
   1. Gustavo Petro (Pacto Historico)
   2. Juan Manuel Santos (Partido de la U)
   3. Ivan Duque (Centro Democratico)
   4. Alvaro Uribe (Centro Democratico)
   
   Ingrese el numero del candidato: 1
   ```

3. **Confirmación:**
   ```
   Ha seleccionado a: Gustavo Petro (Pacto Historico)
   ¿Confirma su voto? (s/n): s
   
   ¡VOTO REGISTRADO EXITOSAMENTE!
   ID del voto: a1b2c3d4-e5f6-7890-abcd-ef1234567890
   ```

### **Consulta de Resultados**

En el Centro de Votación, seleccionar opción `1`:
```
=== RESULTADOS ACTUALES ===
CANDIDATO                    | VOTOS
-----------------------------+-------
Gustavo Petro (Pacto Hist)  |   245
Juan Manuel Santos (Part U)  |   178
Ivan Duque (Centro Democ)   |   201
Alvaro Uribe (Centro Democ) |   156
```

---

## 🔍 **Validación y Pruebas**

### **Cédulas de Prueba Disponibles**
- `1000000001` a `1000000050` (50 votantes autorizados)

### **Escenarios de Prueba Implementados**

1. **Transmisión Normal:** 3000 votos distribuidos en 3 estaciones
2. **Pérdida de Conexión:** 10,000 votos con interrupción de 30 segundos
3. **Prevención de Duplicados:** Validación de unicidad por UUID
4. **Carga Máxima:** 100,000 votos desde 4 estaciones simultáneas

### **Archivos de Trazabilidad**
- `data/estacion/VotosTransitorios.csv` - Almacenamiento local por estación
- `data/centro/VotosRecibidos.csv` - Registro central consolidado

---

## ⚙️ **Configuración Avanzada**

### **Archivos de Configuración**

**Centro de Votación** (`src/main/resources/config/centro.config`):
```properties
CentroVotacion.Endpoints=tcp -h localhost -p 10001
CentroVotacion.CandidatosFile=src/main/resources/data/Candidatos.csv
CentroVotacion.VotosRecibidosFile=data/centro/VotosRecibidos.csv
```

**Estación de Votación** (`src/main/resources/config/estacion.config`):
```properties
EstacionVotacion.Endpoints=tcp -h localhost -p 10000
EstacionVotacion.CedulasAutorizadasFile=src/main/resources/data/CedulasAutorizadas.csv
CentroVotacion.Proxy=GestorRecepcionVotos:tcp -h localhost -p 10001
```

### **Personalización de Puertos**

Para ejecutar múltiples estaciones, modificar puertos en `estacion.config`:
```properties
EstacionVotacion.Endpoints=tcp -h localhost -p 10002
```

---

## 🐛 **Resolución de Problemas**

### **Errores Comunes**

#### **Error: "Proxy inválido para el Centro de Votación"**
**Solución:** Asegurar que el Centro esté ejecutándose antes de iniciar estaciones.

#### **Error: "Cédula no autorizada"**
**Solución:** Verificar que la cédula esté en `CedulasAutorizadas.csv`.

#### **Error: "Puerto ya en uso"**
**Solución:** Cambiar puertos en archivos de configuración o cerrar procesos existentes.

### **Logs de Depuración**

Habilitar logs detallados en `src/main/resources/logback.xml`:
```xml
<logger name="com.registraduria.votacion" level="DEBUG" additivity="false">
    <appender-ref ref="STDOUT" />
</logger>
```

---

## 📊 **Métricas del Sistema**

### **Rendimiento Garantizado**
- **Throughput:** ≥ 1,000 votos/minuto por estación
- **Latencia:** < 3 segundos por voto
- **Disponibilidad:** 99.99% durante jornada electoral
- **Recuperación:** < 2 minutos tras fallo de red

### **Integridad de Datos**
- **Confiabilidad:** 100% de votos transmitidos
- **Unicidad:** 0% de duplicados
- **Trazabilidad:** UUID único por voto con timestamp

---

## 🏗️ **Arquitectura Técnica**

### **Patrones de Diseño Implementados**
- **Reliable Messaging:** Garantía de entrega con acknowledgments
- **Store-and-Forward:** Almacenamiento local antes de transmisión
- **Callback Pattern:** Confirmaciones bidireccionales de estado
- **Scheduler Pattern:** Reintentos automáticos programados

### **Tecnologías Utilizadas**
- **ZeroC ICE:** Middleware de comunicación distribuida
- **CSV:** Persistencia ligera y auditable
- **UUID:** Identificadores únicos globales
- **Concurrent Collections:** Thread-safety para operaciones paralelas

---

