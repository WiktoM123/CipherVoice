package pl.edu.voice.server;

import org.springframework.stereotype.Component;
import pl.edu.voice.common.MemberInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class RoomRegistry {
    private static final Pattern SAFE_NAME = Pattern.compile("[\\p{L}\\p{N}_-]{1,40}");

    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final Map<UUID, ClientSession> clients = new LinkedHashMap<>();

    synchronized Change join(ClientSession session, String roomName, String nickname, String publicKey) {
        validateName("Nazwa pokoju", roomName);
        validateName("Nickname", nickname);
        if (publicKey == null || publicKey.length() > 256) {
            throw new IllegalArgumentException("Nieprawidłowy klucz publiczny");
        }

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
            return new Change(room.name, room.epoch + 1, null, List.of(), List.of());
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
        Room room = rooms.get(sender.room);
        return room != null
                && room.epoch == epoch
                && sender.id.equals(room.leaderId())
                && room.clients.containsKey(target);
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
        return new Change(room.name, room.epoch, room.leaderId(), members, recipients);
    }

    private static void validateName(String label, String value) {
        if (value == null || !SAFE_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " może mieć 1–40 liter, cyfr, _ lub -");
        }
    }

    record Change(String room, int epoch, UUID leaderId,
                  List<MemberInfo> members, Collection<ClientSession> recipients) {
    }

    private static final class Room {
        final String name;
        final LinkedHashMap<UUID, ClientSession> clients = new LinkedHashMap<>();
        int epoch;

        Room(String name) {
            this.name = name;
        }

        UUID leaderId() {
            return clients.keySet().stream().findFirst().orElse(null);
        }
    }
}

