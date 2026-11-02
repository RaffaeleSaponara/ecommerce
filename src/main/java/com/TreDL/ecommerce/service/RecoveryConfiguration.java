package com.TreDL.ecommerce.service;
import org.springframework.context.annotation.*;
import java.time.Clock;
@Configuration
public class RecoveryConfiguration {
    @Bean public Clock recoveryClock(){return Clock.systemUTC();}
}
