# Sistema de Votación Electrónica con Arquitectura Distribuida

## 📋 Descripción del Proyecto

## Presentado por los estudiantes
# Juan Camilo Amorocho Murillo 
# Juan Esteban Ruiz Ome
# Tomas Quintero Gomez

Este proyecto implementa un sistema de votación electrónica distribuido que utiliza middleware ICE (Internet Communications Engine) para garantizar la transmisión segura y confiable de votos desde estaciones de votación remotas hacia un centro de consolidación central. El sistema está diseñado siguiendo patrones de arquitectura empresarial y principios de mensajería confiable (Reliable Messaging).

### 🏗️ Arquitectura del Sistema

El sistema se compone de tres módulos principales:

1. **Estación de Votación (`estacionVotacion`)**: Terminal remota donde los votantes emiten sus votos
2. **Centro de Votación (`centroVotacion`)**: Servidor central que recibe y consolida los votos
3. **Módulo Común (`common`)**: Interfaces y estructuras compartidas definidas en ICE Slice

## 🚀 Características Principales

### Seguridad y Confiabilidad
- **Autenticación de votantes** mediante verificación de cédula contra lista autorizada
- **Almacenamiento transitorio** de votos para garantizar persistencia ante fallos
- **Mecanismo de reintentos automáticos** para votos no confirmados
- **Detección de votos duplicados** para prevenir fraudes
- **Simulación de fallos de red** para pruebas de tolerancia a fallos

### Patrones de Diseño Implementados
- **Reliable Messaging Pattern**: Garantiza la entrega de votos incluso con fallos de red
- **Callback Pattern**: Confirmación asíncrona de recepción de votos
- **Repository Pattern**: Abstracción del almacenamiento de datos
- **MVC Pattern**: Separación clara entre lógica, datos y presentación

### Componentes del Sistema

#### Estación de Votación
- `VotacionConsoleUI`: Interfaz de usuario por consola
- `ControllerEstacion`: Controlador principal de la estación
- `SistemaMonitorizacion`: Sistema de verificación y monitoreo
- `VerificadorAsignacion`: Validación de votantes por mesa
- `AlmacenamientoTransitorio`: Persistencia local de votos
- `GestorEnvioVotos`: Gestión de envío y reintento de votos

#### Centro de Votación
- `GestorRecepcionVotos`: Recepción y procesamiento de votos
- `AlmacenamientoVotos`: Persistencia centralizada
- `ValidadorDeVotos`: Validación de unicidad
- `MotorEmisionVotos`: Procesamiento final de votos

## 📁 Estructura del Proyecto

```
Software_IV_Final_Project/
├── build.gradle                    # Configuración principal de Gradle
├── settings.gradle                 # Configuración de módulos
├── common/                         # Módulo común
│   ├── build.gradle               
│   └── src/
│       ├── main/
│       │   └── slice/
│       │       └── Votacion.ice   # Definiciones ICE
│       └── generated/             # Código generado desde ICE
├── estacionVotacion/              # Módulo de estación
│   ├── build.gradle
│   └── src/main/
│       ├── java/com/votacion/estacion/
│       │   ├── EstacionVotacion.java      # Clase principal
│       │   ├── controller/                # Controladores
│       │   ├── monitoreo/                 # Sistema de monitoreo
│       │   ├── persistencia/              # Almacenamiento local
│       │   ├── ui/                        # Interfaz de usuario
│       │   ├── verificacion/              # Verificación de votantes
│       │   └── votacion/                  # Gestión de votos
│       └── resources/
│           ├── estacion.properties        # Configuración
│           └── data/                      # Datos CSV
├── centroVotacion/                # Módulo del centro
│   ├── build.gradle
│   └── src/main/
│       ├── java/com/votacion/centro/
│       └── resources/
└── gradle/                        # Wrapper de Gradle
```

## 🛠️ Requisitos del Sistema

### Software Requerido
- **JDK 11** o superior
- **Gradle 6.6** o superior
- **ZeroC ICE 3.7.10** (las interfaces están incluidas en el proyecto)

### Hardware Mínimo
- **Memoria RAM**: 512 MB
- **Espacio en disco**: 100 MB
- **Procesador**: Cualquier procesador x64 moderno

### Sistemas Operativos Soportados
- Windows 10/11
- Linux (Ubuntu 20.04+, Debian 10+)
- macOS 10.14+

## 📦 Instalación y Configuración

### 1. Clonar el Repositorio
```bash
git clone https://github.com/tu-usuario/Software_IV_Final_Project.git
cd Software_IV_Final_Project
```

### 2. Compilar el Proyecto

#### Usando Gradle Wrapper (Recomendado)
```bash
# En Windows
./gradlew.bat build

# En Linux/macOS
./gradlew build
```

#### Compilación Manual (si Gradle no está disponible)
```bash
# Crear directorios necesarios
mkdir -p estacionVotacion/build/classes
mkdir -p centroVotacion/build/classes
mkdir -p common/build/classes

# Compilar módulo común
javac -d common/build/classes common/src/main/java/**/*.java

# Compilar estación de votación
javac -cp common/build/classes -d estacionVotacion/build/classes estacionVotacion/src/main/java/**/*.java

# Compilar centro de votación
javac -cp common/build/classes -d centroVotacion/build/classes centroVotacion/src/main/java/**/*.java
```

## 🚀 Ejecución del Sistema

### 1. Iniciar el Centro de Votación (Servidor)
```bash
# Usando Gradle
./gradlew :centroVotacion:run

# O directamente con Java
java -cp "centroVotacion/build/classes:common/build/classes" com.votacion.centro.CentroVotacion
```

### 2. Iniciar la Estación de Votación (Cliente)
En una terminal separada:
```bash
# Usando Gradle
./gradlew :estacionVotacion:run

# O directamente con Java
java -cp "estacionVotacion/build/classes:common/build/classes" com.votacion.estacion.EstacionVotacion
```

## 💻 Uso del Sistema

### Flujo de Votación

1. **Autenticación del Votante**
   - El sistema solicita el número de cédula
   - Verifica que el votante esté autorizado en la mesa

2. **Selección de Candidato**
   - Muestra la lista de candidatos disponibles
   - El votante selecciona su candidato por ID

3. **Confirmación del Voto**
   - El sistema muestra la selección para confirmación
   - El votante confirma o cancela

4. **Transmisión del Voto**
   - El voto se almacena localmente
   - Se envía al centro de votación
   - Se espera confirmación de recepción

### Menú Principal de la Estación

```
===== SISTEMA DE VOTACIÓN =====
1. Iniciar proceso de votación
2. Ver estadísticas de votos
3. Simular fallo de red
4. Forzar reintento de votos pendientes
5. Salir
```

### Simulación de Fallos

El sistema incluye capacidad de simular fallos de red para probar el mecanismo de Reliable Messaging:
- Los votos se almacenan localmente durante el fallo
- Se reintentan automáticamente cada 30 segundos
- Se puede forzar el reintento manualmente

## 📊 Archivos de Datos

El sistema utiliza archivos CSV para almacenar información:

- `CedulasAutorizadas.csv`: Lista de votantes autorizados por mesa
- `Candidatos.csv`: Lista de candidatos disponibles
- `VotosTransitorios.csv`: Almacenamiento temporal de votos pendientes

### Formato de Archivos

**CedulasAutorizadas.csv**
```csv
cedula,nombre,mesa
1000123456,Juan Pérez,MESA-01
1000234567,María García,MESA-01
```

**Candidatos.csv**
```csv
id,nombre,partido
1,Álvaro Uribe Vélez,Centro Democrático
2,Juan Manuel Santos,Partido de la U
3,Gustavo Petro,Colombia Humana
```

## 🔧 Configuración Avanzada

### Archivo de Propiedades

**estacion.properties**
```properties
Centro.Proxy=CentroVotacionService:tcp -h localhost -p 10000
Estacion.Endpoints=tcp -h localhost
Estacion.Id=MESA-01
```

### Cambiar Puerto del Servidor
Modifique el archivo `centro.properties`:
```properties
Centro.Endpoints=tcp -h 0.0.0.0 -p 12000
```

## 🧪 Pruebas y Validación

### Casos de Prueba Incluidos

1. **Votación exitosa**: Flujo completo sin errores
2. **Votante no autorizado**: Validación de acceso
3. **Fallo de red**: Almacenamiento y reintento
4. **Voto duplicado**: Prevención de fraudes
5. **Recuperación ante fallos**: Continuidad del servicio

### Ejecutar Pruebas
```bash
./gradlew test
```

## 🤝 Contribuidores

- **Juan Camilo Amorocho** - Desarrollo del sistema
- **Juan Esteban Ruiz** - Arquitectura y diseño
- **Tomás Quintero** - Implementación y pruebas

**Institución**: Registraduría Nacional  
**Curso**: Ingeniería de Software IV  
**Fecha**: Junio 2025

## 📄 Licencia

Este proyecto fue desarrollado con fines académicos para la Registraduría Nacional como parte del proyecto de votación electrónica.

## 🐛 Solución de Problemas

### Error: "No se encuentra Java"
```bash
# Verificar instalación de Java
java -version

# Configurar JAVA_HOME
export JAVA_HOME=/path/to/java
export PATH=$JAVA_HOME/bin:$PATH
```

### Error: "Puerto en uso"
```bash
# Cambiar el puerto en el archivo de configuración
# O terminar el proceso que usa el puerto
lsof -i :10000  # Linux/macOS
netstat -ano | findstr :10000  # Windows
```

### Error: "Archivo CSV no encontrado"
- Verifique que los archivos CSV estén en el directorio correcto
- Cree los archivos con los formatos especificados si no existen

## 📚 Referencias

- [ZeroC ICE Documentation](https://doc.zeroc.com/ice/3.7)
- [Gradle Build Tool](https://gradle.org/)
- [Reliable Messaging Pattern](https://www.enterpriseintegrationpatterns.com/patterns/messaging/GuaranteedDelivery.html)

---

**Nota**: Este es un sistema de demostración con fines educativos. Para un entorno de producción real, se requieren medidas de seguridad adicionales como cifrado de extremo a extremo, firmas digitales y auditoría completa.
