package org.example;

import com.illposed.osc.OSCMessage;
import com.illposed.osc.OSCMessageListener;
import com.illposed.osc.messageselector.OSCPatternAddressMessageSelector;
import com.illposed.osc.transport.OSCPortIn;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
public class OscListener
{
    private final OSCPortIn oscListener;

    public OscListener(int portIn) throws IOException
    {
        this.oscListener = new OSCPortIn(portIn);
        this.oscListener.setDaemonListener(false);
        this.oscListener.startListening();
        log.info("Listening for OSC messages on port {}...", portIn);
    }

    public <T> void registerListener(String messageSelector, Consumer<T> valueConsumer)
    {
        OSCMessageListener messageListener = (event) ->
        {
            try
            {
                OSCMessage message = event.getMessage();
                String address = message.getAddress();
                List<Object> arguments = message.getArguments();
                if (arguments.isEmpty())
                {
                    log.error("Empty arguments for {}", address);
                    return;
                }
                valueConsumer.accept((T) arguments.getFirst()); // TODO Dynamic check and error
            }
            catch (Exception e) // TODO Better try-catch
            {
                log.error("Exception during osc message handling!", e);
            }
        };
        oscListener.getDispatcher().addListener(new OSCPatternAddressMessageSelector(messageSelector), messageListener);
    }
}
