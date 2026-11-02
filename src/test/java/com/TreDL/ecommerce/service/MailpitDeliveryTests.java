package com.TreDL.ecommerce.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mail.javamail.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MailpitDeliveryTests {
    @Test void realSmtpDeliversResetLinkAndCredentialFreeNotificationToTemporaryMailpit() throws Exception {
        try(var mailpit=new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.27.4"))
                .withExposedPorts(1025,8025).withReuse(false).withLabel("com.3dl.mail-test",UUID.randomUUID().toString())
                .withEnv("MP_DATABASE","/data/mailpit.db").withTmpFs(Map.of("/data","rw,size=64m"))
                .withCreateContainerCmdModifier(c->c.getHostConfig().withPortBindings(
                    new com.github.dockerjava.api.model.PortBinding(com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",0),com.github.dockerjava.api.model.ExposedPort.tcp(1025)),
                    new com.github.dockerjava.api.model.PortBinding(com.github.dockerjava.api.model.Ports.Binding.bindIpAndPort("127.0.0.1",0),com.github.dockerjava.api.model.ExposedPort.tcp(8025))))) {
            mailpit.start();
            JavaMailSenderImpl sender=new JavaMailSenderImpl();sender.setHost("127.0.0.1");sender.setPort(mailpit.getMappedPort(1025));sender.setDefaultEncoding("UTF-8");
            sender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout","3000");sender.getJavaMailProperties().setProperty("mail.smtp.timeout","3000");
            var beans=new DefaultListableBeanFactory();beans.registerSingleton("smtp",sender);
            var mail=new RecoveryMail(beans.getBeanProvider(JavaMailSender.class),true,"no-reply@3dl.test","http://127.0.0.1:9085");
            String testToken="A".repeat(43);
            mail.recovery("customer@example.test",testToken);mail.changed("customer@example.test");
            HttpClient http=HttpClient.newHttpClient();ObjectMapper json=new ObjectMapper();
            String base="http://127.0.0.1:"+mailpit.getMappedPort(8025);
            var listing=http.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/messages")).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(listing.statusCode()).isEqualTo(200);
            var messages=json.readTree(listing.body()).path("messages");assertThat(messages.size()).isEqualTo(2);
            boolean link=false,notification=false;
            for(var message:messages) {
                var detail=http.send(HttpRequest.newBuilder(URI.create(base+"/api/v1/message/"+message.path("ID").asText())).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertThat(detail.statusCode()).isEqualTo(200);
                var body=json.readTree(detail.body());String text=body.path("Text").asText();
                if(body.path("Subject").asText().startsWith("Recupero")) {assertThat(text).contains("http://127.0.0.1:9085/reset-password?token="+testToken);link=true;}
                else {assertThat(text).contains("è stata aggiornata").doesNotContain(testToken,"token=");notification=true;}
            }
            assertThat(link&&notification).isTrue();
        }
    }
    @Test void disabledMailNeverUsesSmtp() {
        var sender=mock(JavaMailSender.class);var beans=new DefaultListableBeanFactory();beans.registerSingleton("smtp",sender);
        var mail=new RecoveryMail(beans.getBeanProvider(JavaMailSender.class),false,"no-reply@3dl.test","http://127.0.0.1:9085");
        mail.recovery("customer@example.test","A".repeat(43));mail.changed("customer@example.test");verifyNoInteractions(sender);
    }
}
