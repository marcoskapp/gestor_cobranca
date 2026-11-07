package br.com.gestorcobranca.collection.web;

import br.com.gestorcobranca.collection.application.port.in.MyDayQueries;
import br.com.gestorcobranca.collection.application.port.in.MyDayQueries.MyDayView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** "Seu dia" do início: contagens do trabalho do operador e das equipes do supervisor (sem dado de devedor). */
@RestController
@RequestMapping(ApiPaths.V1 + "/collection")
class MyDayController {

    private final MyDayQueries myDay;

    MyDayController(MyDayQueries myDay) {
        this.myDay = myDay;
    }

    @GetMapping("/my-day")
    @PreAuthorize("hasAuthority('CASE_WORK') or hasAuthority('CASE_ASSIGN')")
    MyDayView myDay() {
        return myDay.myDay();
    }
}
