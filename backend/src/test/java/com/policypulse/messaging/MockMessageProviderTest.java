package com.policypulse.messaging;

import com.policypulse.common.Domain;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mocks are what a demo and a manual run actually exercise, so their
 * outcomes have to be predictable rather than random: a retry test against a
 * random provider is a flaky test.
 */
class MockMessageProviderTest {

    private final MockEmailProvider email = new MockEmailProvider();
    private final MockSmsProvider sms = new MockSmsProvider();

    private SendResult.Outcome emailTo(String address) {
        return email.send(new OutboundMessage(Domain.Channel.EMAIL, address, "s", "b", 1)).outcome();
    }

    private SendResult.Outcome smsTo(String number) {
        return sms.send(new OutboundMessage(Domain.Channel.SMS, number, null, "b", 1)).outcome();
    }

    @Test
    void eachProviderCarriesOneChannel() {
        assertThat(email.channel()).isEqualTo(Domain.Channel.EMAIL);
        assertThat(sms.channel()).isEqualTo(Domain.Channel.SMS);
    }

    @Test
    void anOrdinaryAddressIsAccepted() {
        assertThat(emailTo("asha@example.test")).isEqualTo(SendResult.Outcome.ACCEPTED);
        assertThat(smsTo("+919876543219")).isEqualTo(SendResult.Outcome.ACCEPTED);
    }

    @Test
    void theDemoAddressesBehaveTheSameWayEveryTime() {
        for (int i = 0; i < 5; i++) {
            assertThat(emailTo("asha@invalid.test")).isEqualTo(SendResult.Outcome.INVALID_ADDRESS);
            assertThat(emailTo("asha@fail.test")).isEqualTo(SendResult.Outcome.FAILED);
            assertThat(smsTo("+919876543212")).isEqualTo(SendResult.Outcome.INVALID_ADDRESS);
            assertThat(smsTo("+919876543213")).isEqualTo(SendResult.Outcome.FAILED);
        }
    }

    @Test
    void theBouncingAddressIsRecognisedWhateverTheCase() {
        assertThat(emailTo("Asha@Invalid.Test")).isEqualTo(SendResult.Outcome.INVALID_ADDRESS);
    }

    /**
     * A log line naming who was contacted, and about what, is a leak in a place
     * nobody checks.
     */
    @Test
    void addressesAreNotWrittenOutInFull() {
        assertThat(Addresses.mask("asha.verma@example.test"))
                .isEqualTo("a***@example.test")
                .doesNotContain("verma");
        assertThat(Addresses.mask("+919876543210")).isEqualTo("***210");
        assertThat(Addresses.mask(null)).isEqualTo("***");
        assertThat(Addresses.mask("  ")).isEqualTo("***");
        assertThat(Addresses.mask("ab")).isEqualTo("***");
    }

    @Test
    void aValueGoingIntoAMessageIsReducedToOneLine() {
        assertThat(Sanitised.oneLine("POL-2\r\nBcc: everyone@example.test"))
                .isEqualTo("POL-2 Bcc: everyone@example.test");
        assertThat(Sanitised.oneLine("  spaced   out  ")).isEqualTo("spaced out");
        assertThat(Sanitised.oneLine("tab\tseparated")).isEqualTo("tab separated");
        assertThat(Sanitised.oneLine("null\u0000byte")).isEqualTo("nullbyte");
        assertThat(Sanitised.oneLine("   ")).isEmpty();
        assertThat(Sanitised.oneLine(null))
                .as("a missing value stays missing rather than becoming empty")
                .isNull();
    }

    @Test
    void aReferenceComesBackSoASendCanBeTracedToTheProvider() {
        assertThat(email.send(new OutboundMessage(Domain.Channel.EMAIL, "a@b.test", "s", "b", 1))
                .providerReference()).startsWith("mock-email-");
        assertThat(sms.send(new OutboundMessage(Domain.Channel.SMS, "+919", null, "b", 1))
                .providerReference()).startsWith("mock-sms-");
    }
}
