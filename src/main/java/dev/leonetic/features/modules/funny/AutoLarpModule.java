package dev.leonetic.features.modules.funny;

import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.util.Random;

public class AutoLarpModule extends Module {

    private final Setting<String> messages = str("Messages",
            "your poor g," +
            "show funds g," +
            "band for band g," +
            "sitting on bands g," +
            "im rich g," +
            "i own this server g," +
            "step down g," +
            "i make your yearly in a day g," +
            "poorboy detected," +
            "i got the funds g," +
            "im rich ong g," +
            "come to my coord base g," +
            "hand the pots g," +
            "im on the bands g," +
            "you got no funds g," +
            "im stacked g," +
            "ez band g," +
            "im rich g let me see your balance," +
            "i run this g," +
            "your broke g," +
            "i buy your whole account g," +
            "sitting on 10k g," +
            "i got the stash g," +
            "let me see your bank g," +
            "poor kid g");
            
    private final Setting<Integer> delay = num("Delay", 15, 1, 120);
    private final Setting<Boolean> antiSpam = bool("AntiSpam", true);

    private long lastMessageTime = 0L;
    private final Random random = new Random();

    public AutoLarpModule() {
        super("AutoLarp", "Automatically LARPs in chat about having bands and funds.", Category.FUNNY);
    }

    @Override
    public void onEnable() {
        lastMessageTime = System.currentTimeMillis();
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck() || mc.player == null || mc.player.connection == null) return;

        ClientPacketListener connection = mc.player.connection;
        
        // Check if delay has passed
        if (System.currentTimeMillis() - lastMessageTime >= delay.getValue() * 1000L) {
            String[] msgArray = messages.getValue().split(",");
            
            if (msgArray.length > 0) {
                // Pick a random message from the list
                String msg = msgArray[random.nextInt(msgArray.length)].trim();
                
                if (!msg.isEmpty()) {
                    // Add anti-spam bypass if enabled
                    if (antiSpam.getValue()) {
                        msg += " " + getRandomString(random.nextInt(3) + 1);
                    }
                    
                    // Send the message to the server
                    connection.sendChat(msg);
                }
                
                // Reset the timer
                lastMessageTime = System.currentTimeMillis();
            }
        }
    }

    /**
     * Generates a random string of characters to bypass basic chat filters.
     */
    private String getRandomString(int length) {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
