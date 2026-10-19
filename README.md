# CipherVoice

Desktopowy komunikator głosowy do rozmów grupowych z szyfrowaniem end-to-end
(E2EE). Projekt powstaje jako praca inżynierska i przypomina uproszczony
TeamSpeak: użytkownik podaje nickname i nazwę pokoju, bez zakładania konta.

Serwer pośredniczy w zestawieniu pokoju i przekazuje pakiety audio, ale nie
otrzymuje grupowego klucza AES ani dźwięku w postaci jawnej.

## Co obecnie działa

- graficzny klient desktopowy JavaFX;
- dołączanie do pokoju bez konta;
- wielu uczestników w jednym pokoju;
- dźwięk mono 48 kHz kodowany kodekiem Opus;
- przesyłanie audio przez UDP;
- kanał sterujący przez TCP;
- szyfrowanie audio AES-256-GCM;
- uzgadnianie kluczy przy użyciu X25519 i HKDF-SHA-256;
- automatyczna zmiana klucza po dołączeniu lub opuszczeniu pokoju;
- ochrona przed ponownym odtworzeniem starego pakietu;
- wyciszanie mikrofonu i głośników;
- wskaźnik osoby mówiącej: podświetlenie, napis i animowane kropki;
- endpoint HTTP pokazujący stan serwera;
- testy kryptografii.

## Technologie

| Element | Technologia | Zastosowanie |
|---|---|---|
| Język | Java 21 | klient, serwer i kod wspólny |
| GUI | JavaFX 21 | okno klienta |
| Serwer | Spring Boot 3 | uruchamianie usług i endpoint HTTP |
| Sterowanie | TCP + JSON | dołączanie, lista uczestników i dystrybucja kluczy |
| Audio | UDP | szybkie przekazywanie ramek głosowych |
| Kodek | Opus / Concentus | kompresja i dekompresja dźwięku |
| Szyfrowanie | AES-256-GCM | poufność i integralność audio oraz kopert klucza |
| Uzgadnianie | X25519 | wspólny sekret między dwiema instancjami klienta |
| Wyprowadzanie | HKDF-SHA-256 | utworzenie klucza do zabezpieczenia klucza grupowego |
| JSON | Jackson | serializacja komunikatów TCP |
| Budowanie | Maven Wrapper | kompilacja bez osobnej instalacji Mavena |

## Architektura

Projekt ma trzy moduły Maven:

```text
CipherVoice
├── common   wspólne komunikaty i stałe protokołu
├── server   serwer Spring Boot, pokoje, TCP i przekaźnik UDP
└── client   JavaFX, audio, Opus, kryptografia i komunikacja sieciowa
```

Połączenia wyglądają następująco:

```text
                         SERWER SPRING BOOT
                    ┌──────────────────────────┐
                    │ TCP 8090: sterowanie     │
┌──────────────┐    │ UDP 8091: relay audio    │    ┌──────────────┐
│   klient A   │◄──►│ HTTP 8080: status        │◄──►│   klient B   │
│ mikrofon     │    │                          │    │ mikrofon     │
│ Opus + E2EE  │    │ nie zna klucza grupowego │    │ Opus + E2EE  │
└──────────────┘    └──────────────────────────┘    └──────────────┘
```

TCP i UDP pełnią różne role:

- **TCP 8090** zapewnia kolejność i niezawodność komunikatów sterujących;
- **UDP 8091** nie czeka na ponowne wysyłanie zagubionych ramek, dlatego nadaje
  się do rozmowy na żywo;
- **HTTP 8080** służy tylko do diagnostyki serwera i nie bierze udziału w audio.

## Jak przebiega dołączenie do pokoju

1. Klient generuje tymczasową parę kluczy X25519.
2. Klient łączy się z serwerem przez TCP 8090.
3. Wysyła komunikat `JOIN` zawierający nazwę pokoju, nickname i klucz publiczny.
4. Serwer sprawdza dane i przydziela:
   - losowy `clientId`;
   - liczbowy `senderTag` używany w pakietach audio;
   - losowy `relayToken` potrzebny do rejestracji adresu UDP.
5. Serwer odsyła `WELCOME` z listą uczestników, numerem epoki i liderem klucza.
6. Pierwszy uczestnik pokoju zostaje liderem klucza.
7. Lider generuje losowy 256-bitowy klucz grupowy AES.
8. Lider przygotowuje osobną zaszyfrowaną kopertę tego klucza dla każdego
   uczestnika.
9. Serwer przekazuje koperty, ale nie potrafi ich odszyfrować.
10. Po otrzymaniu klucza klient może wysyłać i odbierać zaszyfrowane audio.

## Grupowy klucz i zmiana epoki

`Epoka` to numer wersji składu pokoju i klucza grupowego.

Po każdym dołączeniu lub opuszczeniu pokoju serwer:

1. zwiększa numer epoki;
2. wysyła nową listę uczestników;
3. wysyła `REKEY_REQUEST`;
4. lider tworzy nowy klucz AES-256;
5. lider przekazuje nowy klucz wyłącznie aktualnym uczestnikom.

Dzięki temu osoba, która opuściła pokój, nie otrzyma klucza nowej epoki, a nowa
osoba nie dostaje klucza używanego przed jej dołączeniem. Jeśli lider opuści
pokój, liderem zostaje pierwszy pozostały uczestnik.

### Jak zabezpieczana jest koperta klucza

Dla każdej pary `lider → odbiorca` wykonywane są następujące kroki:

```text
prywatny klucz lidera + publiczny klucz odbiorcy
                         │
                         ▼
                       X25519
                         │
                         ▼
                 wspólny sekret pary
                         │
                         ▼
            HKDF-SHA-256 + kontekst pokoju
                         │
                         ▼
             klucz zabezpieczający kopertę
                         │
                         ▼
            AES-GCM(grupowy klucz AES-256)
```

Kontekst zawiera wersję protokołu, pokój, epokę, nadawcę i odbiorcę. Chroni to
przed użyciem koperty w innym pokoju, innej epoce lub dla innej osoby.

## Jak płynie dźwięk

1. `OpusVoiceEngine` pobiera z mikrofonu ramkę PCM długości 20 ms.
2. Parametry wejścia to mono, 48 000 Hz i 16 bitów na próbkę.
3. Concentus koduje ramkę jako Opus VOIP, około 24 kbit/s, VBR i FEC.
4. `AudioPacketCodec` tworzy nagłówek pakietu.
5. Zakodowana ramka Opus jest szyfrowana AES-256-GCM.
6. Klient wysyła datagram UDP do serwera.
7. Serwer sprawdza identyfikator, `senderTag`, adres UDP i epokę.
8. Serwer przesyła ten sam zaszyfrowany datagram pozostałym osobom w pokoju.
9. Odbiorca sprawdza epokę i numer sekwencyjny, odszyfrowuje AES-GCM, dekoduje
   Opus i odtwarza PCM w głośnikach.
10. Poziom odszyfrowanego PCM steruje wskaźnikiem aktywnego mówcy w GUI.

Serwer nie wykonuje kroku odszyfrowania ani dekodowania Opus.

## Format pakietu audio UDP

Nagłówek jest uwierzytelniany przez AES-GCM jako AAD. Nie jest tajny, ale jego
podmiana powoduje odrzucenie pakietu.

| Pole | Rozmiar | Znaczenie |
|---|---:|---|
| `magic` | 4 B | stała `VOIP`, rozpoznanie typu datagramu |
| `version` | 1 B | wersja protokołu |
| `senderId` | 16 B | UUID nadawcy |
| `epoch` | 4 B | aktualna epoka klucza |
| `senderTag` | 4 B | unikalna część nonce nadawcy |
| `sequence` | 8 B | rosnący numer pakietu |
| `ciphertextLength` | 2 B | długość szyfrogramu |
| `ciphertext` | zmienny | Opus zaszyfrowany wraz z tagiem GCM |

Nonce AES-GCM ma 12 bajtów i składa się z `senderTag` (4 B) oraz `sequence`
(8 B). Każdy nadawca ma własny `senderTag`, a licznik jest zerowany przy zmianie
epoki, kiedy używany jest już nowy klucz.

## Komunikaty kanału sterującego

Każdy komunikat TCP jest jedną linią JSON.

| Typ | Kierunek | Znaczenie |
|---|---|---|
| `JOIN` | klient → serwer | prośba o dołączenie do pokoju |
| `WELCOME` | serwer → klient | identyfikator klienta i początkowy stan pokoju |
| `ROOM_STATE` | serwer → klienci | aktualna lista uczestników |
| `REKEY_REQUEST` | serwer → klienci | żądanie utworzenia klucza nowej epoki |
| `KEY_DISTRIBUTION` | lider → serwer → odbiorca | zaszyfrowana koperta klucza grupowego |
| `ERROR` | serwer → klient | błąd dołączenia lub walidacji |

## Gdzie znajduje się kod

### Moduł `common`

| Plik | Odpowiedzialność |
|---|---|
| `Protocol.java` | stałe binarnego protokołu UDP, rozmiary i wersja |
| `MessageType.java` | lista typów komunikatów TCP |
| `WireMessage.java` | obiekt wiadomości JSON przesyłanej przez TCP |
| `MemberInfo.java` | publiczne dane uczestnika: ID, nickname, klucz publiczny i tag |

### Moduł `server`

| Plik | Odpowiedzialność |
|---|---|
| `VoiceServerApplication.java` | główna metoda uruchamiająca Spring Boot |
| `VoiceServerProperties.java` | odczyt portów `voice.control-port` i `voice.udp-port` |
| `ControlServer.java` | nasłuchiwanie TCP, `JOIN`, rozgłaszanie stanu i przekazywanie kopert klucza |
| `RoomRegistry.java` | pokoje, uczestnicy, lider, epoka i sprawdzanie uprawnień |
| `ClientSession.java` | stan pojedynczego połączenia klienta po stronie serwera |
| `UdpRelayServer.java` | rejestracja adresów UDP, walidacja i przekazywanie audio |
| `StatusController.java` | endpoint `GET /api/status` |
| `application.properties` | porty i poziom logowania serwera |

### Moduł `client`

| Plik | Odpowiedzialność |
|---|---|
| `ClientMain.java` | punkt startowy aplikacji JavaFX |
| `VoiceChatApplication.java` | ekran dołączania, pokój, lista osób, przyciski i animacja mówcy |
| `voice.css` | kolory, rozmiary, podświetlenia i wygląd JavaFX |
| `VoiceClient.java` | koordynacja TCP, UDP, stanu pokoju, epok i klucza grupowego |
| `VoiceClientListener.java` | zdarzenia przekazywane z logiki sieciowej do GUI |
| `OpusVoiceEngine.java` | mikrofon, głośniki, kodowanie i dekodowanie Opus, pomiar głośności |
| `CryptoSupport.java` | X25519, HKDF-SHA-256, AES-GCM i generowanie kluczy |
| `AudioPacketCodec.java` | budowanie, szyfrowanie, odczyt i odszyfrowanie datagramów |
| `CryptoSupportTest.java` | test kopert klucza oraz wykrywania modyfikacji szyfrogramu |

Najczęstsze miejsca zmian:

- wygląd aplikacji: `VoiceChatApplication.java` i `voice.css`;
- mikrofon, głośniki lub Opus: `OpusVoiceEngine.java`;
- szyfrowanie: `CryptoSupport.java` i `AudioPacketCodec.java`;
- zachowanie klienta sieciowego: `VoiceClient.java`;
- pokoje i zmiana lidera: `RoomRegistry.java`;
- obsługa TCP: `ControlServer.java`;
- przekazywanie UDP: `UdpRelayServer.java`;
- porty: `server/src/main/resources/application.properties`.

## Wymagania

- Windows 10 lub 11 w wersji x64;
- JDK 21;
- mikrofon i urządzenie odtwarzające;
- Git, jeśli projekt jest pobierany z GitHuba;
- połączenie sieciowe między komputerami.

Kontrola instalacji:

```powershell
java -version
javac -version
git --version
$env:JAVA_HOME
```

`java` i `javac` powinny pokazać wersję 21.

## Pobranie projektu z GitHuba

```powershell
git clone https://github.com/WiktoM123/CipherVoice.git
cd CipherVoice
```

Aktualizacja istniejącej kopii:

```powershell
git pull
```

## Budowanie

Przed budowaniem zamknij działające okna CipherVoice i zatrzymaj serwer przez
`Ctrl+C`. Windows może blokować używane pliki JAR.

W katalogu głównym projektu:

```powershell
.\mvnw.cmd clean package
```

Maven Wrapper pobiera właściwą wersję Mavena przy pierwszym użyciu. Po udanym
budowaniu pojawi się `BUILD SUCCESS` oraz dwa główne pliki:

```text
server\target\server-0.1.0-SNAPSHOT.jar
client\target\CipherVoice.jar
```

Uruchomienie testów bez pakowania:

```powershell
.\mvnw.cmd test
```

## Uruchomienie na jednym komputerze

Otwórz dwa okna PowerShell w katalogu projektu.

Terminal 1 — serwer:

```powershell
java -jar server\target\server-0.1.0-SNAPSHOT.jar
```

Terminal 2 — klient:

```powershell
java -jar client\target\CipherVoice.jar
```

W oknie klienta wpisz:

```text
Serwer:   localhost
Pokój:    pokoj
Nickname: dowolna unikalna nazwa
TCP:      8090
UDP:      8091
```

Można uruchomić drugi klient, ale oba procesy będą korzystać z urządzeń audio
tego samego komputera, co może powodować echo. Prawidłowy test głosu najlepiej
wykonać na dwóch komputerach i w słuchawkach.

## Uruchomienie na dwóch komputerach w jednej sieci

### Komputer 1 — serwer

1. Uruchom serwer:

   ```powershell
   java -jar server\target\server-0.1.0-SNAPSHOT.jar
   ```

2. Sprawdź adres IPv4:

   ```powershell
   ipconfig
   ```

   Przykładowy adres to `192.168.1.108`. Adres może się zmienić po ponownym
   połączeniu z routerem.

3. Jeśli Zapora Windows blokuje połączenie, uruchom PowerShell jako
   administrator i dodaj reguły tylko dla sieci prywatnej:

   ```powershell
   New-NetFirewallRule -DisplayName "CipherVoice TCP 8090" -Direction Inbound -Protocol TCP -LocalPort 8090 -Action Allow -Profile Private
   New-NetFirewallRule -DisplayName "CipherVoice UDP 8091" -Direction Inbound -Protocol UDP -LocalPort 8091 -Action Allow -Profile Private
   ```

4. Uruchom klienta na komputerze 1. Może używać `localhost` albo własnego
   adresu IPv4.

### Komputer 2 — klient

1. Zainstaluj JDK 21.
2. Skopiuj `client\target\CipherVoice.jar` z komputera 1 lub zbuduj projekt.
3. Uruchom plik z katalogu, w którym rzeczywiście się znajduje:

   ```powershell
   java -jar CipherVoice.jar
   ```

4. W polu `Serwer` wpisz IPv4 komputera 1, np. `192.168.1.108`.
5. Użyj tej samej nazwy pokoju na obu komputerach i różnych nickname'ów.

Test portu TCP z komputera 2:

```powershell
Test-NetConnection 192.168.1.108 -Port 8090
```

Prawidłowy wynik zawiera:

```text
TcpTestSucceeded : True
```

## Status serwera

W przeglądarce na komputerze-serwerze otwórz:

```text
http://localhost:8080/api/status
```

Przykładowa odpowiedź:

```json
{
  "status": "ok",
  "rooms": 1,
  "participants": 2,
  "controlPort": 8090,
  "udpPort": 8091
}
```

## Typowe problemy

### `Unable to access jarfile CipherVoice.jar`

Polecenie zostało uruchomione w złym katalogu. Z katalogu głównego projektu użyj:

```powershell
java -jar client\target\CipherVoice.jar
```

Jeśli plik leży na Pulpicie, podaj pełną ścieżkę:

```powershell
java -jar "C:\Users\PC\Desktop\CipherVoice.jar"
```

### `Port already in use` / port jest zajęty

Najczęściej serwer jest już uruchomiony. Nie uruchamiaj drugiej kopii. W terminalu
działającego serwera naciśnij `Ctrl+C` albo znajdź PID:

```powershell
netstat -ano | findstr ":8080 :8090 :8091"
```

Zatrzymaj wyłącznie PID serwera:

```powershell
Stop-Process -Id NUMER_PID
```

Nie używaj `taskkill /IM java.exe`, ponieważ zatrzyma również inne programy Java
i rozszerzenie Java w VS Code.

### `Connection refused` / serwer nie odpowiada

Sprawdź kolejno:

1. czy serwer jest uruchomiony;
2. czy klient używa prawidłowego IPv4 serwera;
3. czy TCP 8090 jest dozwolony w Zaporze Windows;
4. czy oba komputery są w tej samej sieci;
5. czy router nie ma włączonej izolacji klientów Wi-Fi.

### Klienci się widzą, ale nie słychać dźwięku

Sprawdź:

1. czy UDP 8091 jest dozwolony w zaporze;
2. czy mikrofon nie jest wyciszony w aplikacji;
3. czy Windows zezwala aplikacjom klasycznym na dostęp do mikrofonu;
4. czy wybrano działające domyślne urządzenia nagrywania i odtwarzania;
5. czy oba klienty mają aktualną wersję `CipherVoice.jar`.

### Ostrzeżenie `Unsupported JavaFX configuration`

To ostrzeżenie wynika z umieszczenia JavaFX i zależności w jednym dużym JAR-ze.
Nie zatrzymuje aplikacji i nie wpływa na szyfrowanie. Docelowy instalator
`jpackage` z własnym runtime usunie tę niedogodność.

## Model bezpieczeństwa

### Co jest chronione

- zawartość ramek Opus jest szyfrowana AES-256-GCM;
- modyfikacja szyfrogramu lub nagłówka jest wykrywana przez tag GCM;
- grupowy klucz nie jest wysyłany do serwera w postaci jawnej;
- serwer przekazuje tylko osobne zaszyfrowane koperty klucza;
- numer sekwencyjny pomaga odrzucać powtórzone lub stare pakiety;
- zmiana składu pokoju powoduje utworzenie nowego klucza;
- losowy `relayToken` ogranicza możliwość podszycia się podczas rejestracji UDP.

### Co widzi serwer

Serwer widzi metadane potrzebne do działania:

- adresy IP i porty klientów;
- nickname oraz nazwę pokoju;
- klucze publiczne X25519;
- identyfikatory uczestników, epokę i rozmiary pakietów;
- czas i częstotliwość przesyłania datagramów.

Nie widzi klucza grupowego ani odszyfrowanej zawartości rozmowy.

### Ważne ograniczenie: aktywny MITM

Klienci nie mają kont, certyfikatów ani wcześniej zaufanych kluczy. Obecna
wersja nie potwierdza kryptograficznie tożsamości otrzymanego klucza publicznego.
Złośliwy, aktywny serwer mógłby próbować podmienić klucze publiczne i wykonać
atak man-in-the-middle.

W pracy inżynierskiej należy jasno określić model jako ochronę przed pasywnym
lub uczciwym, lecz ciekawskim serwerem. Wersja docelowa powinna dodać porównanie
krótkiego kodu bezpieczeństwa (SAS), kod QR, podpisy lub inny mechanizm
uwierzytelnienia uczestników.

## Obecne ograniczenia i dalszy rozwój

- brak wyboru urządzenia wejściowego i wyjściowego w GUI;
- brak bufora jitter i adaptacyjnego wyrównywania opóźnień;
- brak prawdziwego miksowania kilku jednoczesnych strumieni PCM;
- brak automatycznego odnajdywania serwera w LAN;
- brak NAT traversal; połączenie przez internet wymaga obecnie dostępnego serwera;
- brak kont, ról, haseł do pokojów i list kontroli dostępu;
- brak SAS/QR do ochrony przed aktywnym MITM;
- brak instalatora `.exe` i automatycznych aktualizacji;
- klientowy JAR zawiera natywne biblioteki JavaFX dla Windows x64.

Naturalna kolejność dalszych prac:

1. bufor jitter i miksowanie wielu rozmówców;
2. wybór mikrofonu oraz głośników;
3. SAS lub QR do weryfikacji kluczy;
4. testy integracyjne klient–serwer;
5. serwer dostępny przez internet;
6. instalator Windows przez `jpackage`;
7. pomiary opóźnienia, strat pakietów i jakości dźwięku do pracy inżynierskiej.

## Uruchamianie i zatrzymywanie — skrót

```powershell
# budowanie
.\mvnw.cmd clean package

# serwer
java -jar server\target\server-0.1.0-SNAPSHOT.jar

# klient
java -jar client\target\CipherVoice.jar

# testy
.\mvnw.cmd test
```

Serwer zatrzymuje się przez `Ctrl+C`. Klient kończy pracę po użyciu przycisku
`Rozłącz` i zamknięciu okna.
