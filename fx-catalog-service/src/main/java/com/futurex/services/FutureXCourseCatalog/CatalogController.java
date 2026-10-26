package com.futurex.services.FutureXCourseCatalog;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClient;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
public class CatalogController {

    @Autowired
    private EurekaClient client;

    @GetMapping("/")
    @CircuitBreaker(name = "courseService", fallbackMethod = "fallbackGetCatalogHome")
    public String getCatalogHome() {
        String courseAppMesage = "";
        //String courseAppURL = "http://localhost:8080/";
        RestTemplate restTemplate = new RestTemplate();
        InstanceInfo instanceInfo = client.getNextServerFromEureka("fx-course-service",false);
        String courseAppURL = instanceInfo.getHomePageUrl();
        courseAppMesage = restTemplate.getForObject(courseAppURL,String.class);

        return("Welcome to FutureX Course Catalog "+courseAppMesage);
    }

    @GetMapping("/catalog")
    @CircuitBreaker(name = "courseService", fallbackMethod = "fallbackGetCatalog")
    public String getCatalog() {
        String courses = "";
        //String courseAppURL = "http://localhost:8080/courses";
        InstanceInfo instanceInfo = client.getNextServerFromEureka("fx-course-service",false);
        String courseAppURL = instanceInfo.getHomePageUrl();
        courseAppURL = courseAppURL+"/courses";
        RestTemplate restTemplate = new RestTemplate();
        courses = restTemplate.getForObject(courseAppURL,String.class);

        return("Our courses are "+courses);
    }

    @GetMapping("/firstcourse")
    @CircuitBreaker(name = "courseService", fallbackMethod = "fallbackGetSpecificCourse")
    public String getSpecificCourse() {
        Course course = new Course();
        //String courseAppURL = "http://localhost:8080/1";
        InstanceInfo instanceInfo = client.getNextServerFromEureka("fx-course-service",false);
        String courseAppURL = instanceInfo.getHomePageUrl();
        courseAppURL = courseAppURL+"/1";
        RestTemplate restTemplate = new RestTemplate();

        course = restTemplate.getForObject(courseAppURL,Course.class);

        return("Our first course is "+course.getCoursename());
    }

    // Fallbacks: misma firma que el metodo protegido + la excepcion como ultimo parametro.

    public String fallbackGetCatalogHome(Exception ex) {
        return "Welcome to FutureX Course Catalog (Fallback: course service unavailable" + reason(ex) + ")";
    }

    public String fallbackGetCatalog(Exception ex) {
        return "Fallback: Unable to retrieve course catalog." + reason(ex);
    }

    public String fallbackGetSpecificCourse(Exception ex) {
        return "Fallback: Unable to retrieve the first course." + reason(ex);
    }

    // Distingue si el fallback se ejecuto por un fallo real o porque el circuito esta abierto
    private String reason(Exception ex) {
        if (ex instanceof CallNotPermittedException) {
            return " [Circuit OPEN - call not attempted]";
        }
        return " [Error: " + ex.getClass().getSimpleName() + "]";
    }

}
