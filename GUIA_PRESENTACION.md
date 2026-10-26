# Taller: Service Discovery y Circuit Breaker en Microservicios

## Estructura

| Carpeta | Servicio | Puerto | Rol |
|---|---|---|---|
| `fx-discovery-server` | FutureXEurekaServer | 8761 | Servidor Eureka (registro de servicios) |
| `fx-course-service` | FutureXCourseApp | 8001 | Proveedor: cursos en H2 |
| `fx-catalog-service` | FutureXCourseCatalog | 8002 | Consumidor: llama a fx-course-service (aqui va Resilience4j) |

Stack: Spring Boot 3.3.3, Spring Cloud 2023.0.3, Java 17+.

## Como ejecutar

Orden obligatorio: Eureka → course → catalog. Desde la carpeta de cada servicio:

```bash
./mvnw spring-boot:run
```

(En Windows: `mvnw.cmd spring-boot:run`, o ejecutar la clase `*Application` desde el IDE.)

Tras arrancar, espere ~30 s a que el catalogo descargue el registro de Eureka.

---

## Parte 1 – Service Discovery (40%)

### Como se registran los servicios
- `fx-discovery-server` tiene `@EnableEurekaServer` y `eureka.client.register-with-eureka=false` / `fetch-registry=false`: es el registro, no se registra a si mismo.
- `fx-course-service` y `fx-catalog-service` tienen el cliente Eureka y en `application.properties`:
  - `spring.application.name=fx-course-service` → **nombre logico** con el que se registra.
  - `eureka.client.service-url.defaultZone=http://localhost:8761/eureka/` → donde esta el registro.
- Al arrancar, cada cliente envia su IP, puerto y URL al servidor y luego un **heartbeat cada 30 s**. Si deja de enviarlo, Eureka lo saca del registro.

### Como fx-catalog-service descubre a fx-course-service
```java
InstanceInfo instanceInfo = client.getNextServerFromEureka("fx-course-service", false);
String courseAppURL = instanceInfo.getHomePageUrl();      // http://localhost:8001/
RestTemplate restTemplate = new RestTemplate();
courses = restTemplate.getForObject(courseAppURL + "/courses", String.class);
```
1. El catalogo **no conoce la URL** del servicio de cursos (la URL fija quedo comentada: `//String courseAppURL = "http://localhost:8080/courses"`).
2. Pregunta a Eureka por el **nombre logico** `fx-course-service`.
3. `getNextServerFromEureka` devuelve una instancia (si hay varias, las rota: balanceo round-robin simple). El `false` indica que no se requiere HTTPS.
4. Con la `homePageUrl` de esa instancia hace la llamada REST.

**Ventaja:** si el servicio de cursos cambia de puerto/host o se escala a varias instancias, el catalogo no cambia. **Desacoplamiento de ubicacion.**

### Demostracion
| URL | Resultado esperado |
|---|---|
| http://localhost:8761/ | Panel Eureka con FX-COURSE-SERVICE y FX-CATALOG-SERVICE en estado UP |
| http://localhost:8002/ | `Welcome to FutureX Course Catalog Course App Home` |
| http://localhost:8002/catalog | `Our courses are [{"courseid":1,...}]` |
| http://localhost:8002/firstcourse | `Our first course is Spring Boot Fundamentals` |

---

## Parte 2 – Circuit Breaker con Resilience4j (60%)

### Cambios realizados (solo en fx-catalog-service)

**1. `pom.xml`** – dependencias:
- `spring-cloud-starter-circuitbreaker-resilience4j` (Resilience4j + anotaciones)
- `spring-boot-starter-aop` (las anotaciones funcionan con proxies AOP)
- `spring-boot-starter-actuator` (monitoreo)

**2. `application.properties`** – instancia `courseService`:

| Propiedad | Valor | Significado |
|---|---|---|
| `slidingWindowSize` | 10 | Evalua las ultimas 10 llamadas |
| `minimumNumberOfCalls` | 5 | No calcula tasa de fallo hasta tener 5 llamadas |
| `failureRateThreshold` | 50 | Si ≥50% fallan → se **abre** el circuito |
| `waitDurationInOpenState` | 5s | Tiempo abierto antes de probar de nuevo |
| `automaticTransitionFromOpenToHalfOpenEnabled` | true | Pasa a HALF_OPEN solo, sin esperar una llamada |
| `permittedNumberOfCallsInHalfOpenState` | 3 | Llamadas de prueba en HALF_OPEN |
| `registerHealthIndicator` | true | Expone el estado en `/actuator/health` |
| `eventConsumerBufferSize` | 10 | Eventos guardados para `/actuator/circuitbreakerevents` |

Mas actuator: `management.health.circuitbreakers.enabled=true`, exposicion de `health,circuitbreakers,circuitbreakerevents` y `show-details=always`.

**3. `CatalogController.java`** – los tres endpoints llevan:
```java
@GetMapping("/catalog")
@CircuitBreaker(name = "courseService", fallbackMethod = "fallbackGetCatalog")
public String getCatalog() { ... }

public String fallbackGetCatalog(Exception ex) {
    return "Fallback: Unable to retrieve course catalog." + reason(ex);
}
```
- `name` enlaza con la configuracion `resilience4j.circuitbreaker.instances.courseService`.
- El fallback debe tener **mismo tipo de retorno y mismos parametros + la excepcion** al final.
- `reason(ex)` distingue si el fallback se ejecuto por un **fallo real** (`ResourceAccessException`: conexion rechazada) o porque el circuito estaba **abierto** (`CallNotPermittedException`: la llamada ni se intento). Muy util para la demo.

**4. `Resilience4JConfig.java`** (opcional del taller) – `Customizer<Resilience4JCircuitBreakerFactory>` con configuracion por defecto (50%, 1 s abierto, ventana 2, timeout 4 s). Aplica a circuit breakers creados de forma programatica con `CircuitBreakerFactory`; los metodos anotados usan la instancia `courseService` de `application.properties`.

### Estados del Circuit Breaker
```
           fallos >= 50% (min. 5 llamadas)
  CLOSED ─────────────────────────────────▶ OPEN
    ▲                                         │ pasan 5 s
    │ 3 llamadas de prueba OK                 ▼
    └──────────────────────────────────── HALF_OPEN
                 (si fallan ≥50% ──▶ vuelve a OPEN)
```
- **CLOSED:** las llamadas pasan normalmente; se cuentan fallos.
- **OPEN:** las llamadas **no se ejecutan**; responde el fallback de inmediato (no se espera a timeouts, no se satura el servicio caido).
- **HALF_OPEN:** se dejan pasar algunas llamadas de prueba para ver si el servicio se recupero.

### Guion de demostracion (verificado)

1. Con todo arriba: http://localhost:8002/catalog → lista de cursos. http://localhost:8002/actuator/health → `courseService: CLOSED`.
2. **Detener** `fx-course-service`.
3. Refrescar http://localhost:8002/catalog varias veces:
   - Llamadas 1–5: `Fallback: Unable to retrieve course catalog. [Error: ResourceAccessException]`
   - Llamada 6 en adelante: `... [Circuit OPEN - call not attempted]`
   - `/actuator/health` → `state: OPEN, failureRate: 100.0%`
4. Esperar 5 s → `/actuator/health` muestra `HALF_OPEN`.
5. **Reiniciar** `fx-course-service` y esperar a que aparezca en Eureka (~30 s).
6. Llamar a los endpoints → respuestas normales; `/actuator/health` → `CLOSED`.
7. http://localhost:8002/actuator/circuitbreakerevents → historial:
   `CLOSED_TO_OPEN`, `OPEN_TO_HALF_OPEN`, `HALF_OPEN_TO_CLOSED`.

Resultado obtenido en la prueba real:
```
call 1-5: Fallback ... [Error: ResourceAccessException]
call 6-7: Fallback ... [Circuit OPEN - call not attempted]
   state=OPEN failureRate=100.0% buffered=5 failed=5 notPermitted=2
(5 s despues) state=HALF_OPEN
(curso reiniciado) Our first course is Spring Boot Fundamentals  → state=CLOSED
Eventos: CLOSED_TO_OPEN → OPEN_TO_HALF_OPEN → HALF_OPEN_TO_CLOSED
```

> Dato extra para la demo: justo despues de arrancar, si el catalogo aun no ha descargado el registro de Eureka, `getNextServerFromEureka` lanza excepcion ("No matches for the virtual host name") y el Circuit Breaker tambien la captura con el fallback. Por eso se recomienda esperar ~30 s.

---

## Como Resilience4j mejora la resiliencia

- **Evita fallos en cascada:** si fx-course-service cae, fx-catalog-service sigue respondiendo (degradado) en lugar de devolver error 500.
- **Fail fast:** con el circuito abierto no se gastan hilos/conexiones esperando a un servicio caido.
- **Da tiempo de recuperacion** al servicio caido al dejar de enviarle trafico.
- **Auto-recuperacion:** HALF_OPEN detecta cuando el servicio vuelve, sin intervencion manual.
- **Observabilidad:** estado y eventos via Actuator (integrable con Prometheus/Grafana).
- **Modular y ligero:** ademas de CircuitBreaker ofrece `@Retry`, `@TimeLimiter`, `@Bulkhead` y `@RateLimiter`, combinables. Basado en programacion funcional (Java 8+), sin dependencias pesadas.

## Como Hystrix mejoraba la resiliencia (comparacion)

Hystrix (Netflix) fue la implementacion original del patron en Spring Cloud:
- `@HystrixCommand(fallbackMethod = "...")` sobre el metodo, con la misma idea de fallback.
- Aislaba cada dependencia en un **pool de hilos propio (bulkhead por thread pool)**, de modo que un servicio lento no agotaba los hilos de Tomcat.
- Timeouts por comando, metricas en ventana deslizante y **Hystrix Dashboard / Turbine** para visualizar circuitos.

| | Hystrix | Resilience4j |
|---|---|---|
| Estado | **Mantenimiento desde 2018**, sin soporte en Spring Boot 3 | Activo, soportado por Spring Cloud CircuitBreaker |
| Aislamiento | Thread pool por comando (mas overhead) | Semaforo por defecto; Bulkhead opcional (semaforo o thread pool) |
| Diseno | Monolitico, todo en un `HystrixCommand` | Modulos independientes (CB, Retry, RateLimiter, TimeLimiter, Bulkhead) |
| Configuracion | Archaius | Propiedades Spring Boot / YAML |
| Monitoreo | Hystrix Dashboard | Actuator + Micrometer |

Ambos implementan el mismo patron (CLOSED/OPEN/HALF_OPEN + fallback); Resilience4j es el reemplazo recomendado por ser mas ligero, modular y compatible con Spring Boot 3.
