package pl.ciphervoice.server;

import org.springframework.stereotype.Component;
import pl.ciphervoice.common.MemberInfo;

import javax.crypto.KeyAgreement;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.interfaces.XECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@Component
public class RoomRegistry {
    private static final Pattern SAFE_NAME = Pattern.compile("[\\p{L}\\p{N}_-]{1,40}");

    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final Map<UUID, ClientSession> clients = new LinkedHashMap<>();

    Change join(ClientSession session, String roomName, String nickname, String publicKey) {
        validateName("Nazwa pokoju", roomName);
        validateName("Nickname", nickname);
        validatePublicKey(publicKey);

        synchronized (this) {
            Room room = rooms.computeIfAbsent(roomName, Room::new);
            boolean duplicate = room.clients.values().stream()
                    .anyMatch(client -> client.nickname.equalsIgnoreCase(nickname));
            if (duplicate) {
                throw new IllegalArgumentException("Ten nickname jest już zajęty w pokoju");
            }

            session.room = roomName;
            session.nickname = nickname;
            session.publicKey = publicKey;
            room.clients.put(session.id, session);
            clients.put(session.id, session);
            room.epoch++;
            return change(room);
        }
    }

    synchronized Change leave(ClientSession session) {
        clients.remove(session.id);
        if (session.room == null) {
            return null;
        }
        Room room = rooms.get(session.room);
        if (room == null || room.clients.remove(session.id) == null) {
            return null;
        }
        if (room.clients.isEmpty()) {
            rooms.remove(room.name);
        }
        room.epoch++;
        return change(room);
    }

    synchronized ClientSession find(UUID id) {
        return clients.get(id);
    }

    synchronized ClientSession findInRoom(String roomName, UUID id) {
        Room room = rooms.get(roomName);
        return room == null ? null : room.clients.get(id);
    }

    synchronized boolean mayDistribute(ClientSession sender, UUID target, int epoch) {
        return distributionTarget(sender, target, epoch) != null;
    }

    synchronized ClientSession distributionTarget(ClientSession sender, UUID target, int epoch) {
        Room room = rooms.get(sender.room);
        if (room == null || room.epoch != epoch || !sender.id.equals(room.leaderId())) {
            return null;
        }
        return room.clients.get(target);
    }

    synchronized boolean isCurrentEpoch(ClientSession sender, int epoch) {
        Room room = rooms.get(sender.room);
        return room != null && room.epoch == epoch && room.clients.containsKey(sender.id);
    }

    synchronized List<ClientSession> peers(ClientSession sender) {
        Room room = rooms.get(sender.room);
        if (room == null) {
            return List.of();
        }
        return room.clients.values().stream()
                .filter(client -> !client.id.equals(sender.id))
                .toList();
    }

    public synchronized int roomCount() {
        return rooms.size();
    }

    public synchronized int participantCount() {
        return clients.size();
    }

    private static Change change(Room room) {
        List<ClientSession> recipients = new ArrayList<>(room.clients.values());
        List<MemberInfo> members = room.clients.values().stream()
                .map(client -> new MemberInfo(client.id.toString(), client.nickname,
                        client.publicKey, client.senderTag))
                .toList();
        Publication publication = new Publication(room.lastPublication);
        room.lastPublication = publication.finished;
        return new Change(room.name, room.epoch, room.leaderId(), members, recipients, publication);
    }

    private static void validateName(String label, String value) {
        if (value == null || !SAFE_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " może mieć 1–40 liter, cyfr, _ lub -");
        }
    }

    private static void validatePublicKey(String encoded) {
        if (encoded == null || encoded.isEmpty() || encoded.length() > 256) {
            throw new IllegalArgumentException("Nieprawidłowy klucz publiczny X25519");
        }
        try {
            PublicKey key = KeyFactory.getInstance("X25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            if (!(key instanceof XECPublicKey xecKey)
                    || !(xecKey.getParams() instanceof NamedParameterSpec parameters)
                    || !parameters.getName().equalsIgnoreCase("X25519")) {
                throw new IllegalArgumentException("Nieprawidłowy klucz publiczny X25519");
            }
            // Parsing alone accepts low-order points, which would fail rekey on every leader.
            KeyAgreement agreement = KeyAgreement.getInstance("X25519");
            agreement.init(KeyPairGenerator.getInstance("X25519").generateKeyPair().getPrivate());
            agreement.doPhase(key, true);
            byte[] sharedSecret = agreement.generateSecret();
            Arrays.fill(sharedSecret, (byte) 0);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Nieprawidłowy klucz publiczny X25519", exception);
        }
    }

    record Change(String room, int epoch, UUID leaderId,
                  List<MemberInfo> members, Collection<ClientSession> recipients,
                  Publication publication) {
        void publish(Runnable action) {
            publication.publish(action);
        }
    }

    /** Only a room's publications wait for each other; no registry lock covers socket I/O. */
    private static final class Publication {
        private final CompletableFuture<Void> previous;
        private final CompletableFuture<Void> finished = new CompletableFuture<>();
        private final AtomicBoolean started = new AtomicBoolean();

        Publication(CompletableFuture<Void> previous) {
            this.previous = previous;
        }

        void publish(Runnable action) {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            previous.join();
            try {
                action.run();
            } finally {
                finished.complete(null);
            }
        }
    }

    private static final class Room {
        final String name;
        final LinkedHashMap<UUID, ClientSession> clients = new LinkedHashMap<>();
        int epoch;
        CompletableFuture<Void> lastPublication = CompletableFuture.completedFuture(null);

        Room(String name) {
            this.name = name;
        }

        UUID leaderId() {
            return clients.keySet().stream().findFirst().orElse(null);
        }
    }
}
