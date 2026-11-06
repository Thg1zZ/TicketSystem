package dev.thiagogomes.ticketsystem.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

import static dev.thiagogomes.ticketsystem.messaging.Messaging.DEAD_LETTER_EXCHANGE;
import static dev.thiagogomes.ticketsystem.messaging.Messaging.EXCHANGE;
import static dev.thiagogomes.ticketsystem.messaging.Messaging.ORDER_CANCELLED;
import static dev.thiagogomes.ticketsystem.messaging.Messaging.ORDER_CANCELLED_EMAIL_QUEUE;
import static dev.thiagogomes.ticketsystem.messaging.Messaging.ORDER_CONFIRMED;
import static dev.thiagogomes.ticketsystem.messaging.Messaging.TICKETS_ISSUED;
import static dev.thiagogomes.ticketsystem.messaging.Messaging.TICKETS_ISSUED_EMAIL_QUEUE;
import static dev.thiagogomes.ticketsystem.messaging.Messaging.TICKET_ISSUANCE_QUEUE;
import static dev.thiagogomes.ticketsystem.messaging.Messaging.deadLetterQueue;

/**
 * One topic exchange routes each event to its queues. A queue that gives up on a message (retries exhausted
 * or unreadable payload) sends it to the dead-letter exchange, which parks it in that queue's DLQ for inspection.
 */
@Configuration(proxyBeanMethods = false)
class RabbitTopologyConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitTopologyConfig.class);

    @Bean
    Declarables ticketTopology() {
        TopicExchange events = new TopicExchange(EXCHANGE);
        DirectExchange deadLetters = new DirectExchange(DEAD_LETTER_EXCHANGE);

        List<Declarable> declarables = new ArrayList<>(List.of(events, deadLetters));
        declarables.addAll(queueWithDeadLetter(TICKET_ISSUANCE_QUEUE, ORDER_CONFIRMED, events, deadLetters));
        declarables.addAll(queueWithDeadLetter(TICKETS_ISSUED_EMAIL_QUEUE, TICKETS_ISSUED, events, deadLetters));
        declarables.addAll(queueWithDeadLetter(ORDER_CANCELLED_EMAIL_QUEUE, ORDER_CANCELLED, events, deadLetters));
        return new Declarables(declarables);
    }

    /**
     * Payloads are JSON; listeners receive them already converted to the parameter type.
     */
    @Bean
    MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    /**
     * With mandatory=true the broker hands back messages no queue accepted; they must not vanish silently.
     * The outbox row keeps the payload, so the event can be replayed after fixing the topology.
     */
    @Bean
    RabbitTemplateCustomizer unroutableMessageAlarm() {
        return template -> template.setReturnsCallback(returned -> log.error(
                "Unroutable message {} (exchange={}, routingKey={}): {}",
                returned.getMessage().getMessageProperties().getMessageId(),
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
    }

    private static List<Declarable> queueWithDeadLetter(String name, String routingKey,
                                                        TopicExchange events, DirectExchange deadLetters) {
        Queue queue = QueueBuilder.durable(name)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(name)
                .build();
        Queue deadLetterQueue = QueueBuilder.durable(deadLetterQueue(name)).build();
        Binding binding = BindingBuilder.bind(queue).to(events).with(routingKey);
        Binding deadLetterBinding = BindingBuilder.bind(deadLetterQueue).to(deadLetters).with(name);
        return List.of(queue, deadLetterQueue, binding, deadLetterBinding);
    }
}
