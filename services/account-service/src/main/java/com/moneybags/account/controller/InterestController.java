package com.moneybags.account.controller;

import com.moneybags.account.dto.InterestAccrualView;
import com.moneybags.account.dto.InterestRunRequest;
import com.moneybags.account.dto.InterestRunView;
import com.moneybags.account.security.RequestActor;
import com.moneybags.account.security.RequestActorResolver;
import com.moneybags.account.service.InterestCalculationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class InterestController {
    private final InterestCalculationService interest;
    private final RequestActorResolver actors;

    @PostMapping("/interest-runs")
    public InterestRunView run(@Valid @RequestBody InterestRunRequest request,
                               HttpServletRequest http) {
        RequestActor actor = actors.resolve(http);
        actor.require(RequestActor.PERMISSION_STATUS_MANAGE);
        return interest.run(request.periodEndDate(), actor.correlationId());
    }

    @GetMapping("/{accountId}/interest-accruals")
    public List<InterestAccrualView> accruals(@PathVariable String accountId,
                                             HttpServletRequest http) {
        return interest.list(actors.resolve(http), accountId);
    }
}
