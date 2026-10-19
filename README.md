# Grupowy komunikator głosowy E2EE

Prototyp pracy inżynierskiej: grupowa rozmowa głosowa bez kont użytkowników.
Serwer Spring Boot zarządza pokojami i przekazuje zaszyfrowane datagramy UDP,
ale nie otrzymuje klucza grupowego ani jawnego dźwięku.

## Architektura

```text
klient A ── TCP 8090 (sterowanie i klucze publiczne) ──┐
klient A ── UDP 8091 (Opus zaszyfrowany AES-GCM) ──────┤
                                                        ├── serwer Spring Boot
klient B ── TCP 8090 (sterowanie i klucze publiczne) ──┤
klient B ── UDP 8091 (Opus zaszyfrowany AES-GCM) ──────┘
```

- `common` — komunikaty protokołu i stałe współdzielone;
- `server` — pokoje, kanał sterujący TCP oraz przekaźnik UDP;
- `client` — Java Sound, kodek Opus, X25519, HKDF-SHA-256 i AES-256-GCM.

Pierwszy uczestnik pokoju jest liderem klucza. Po każdej zmianie składu pokoju
serwer zwiększa numer epoki. Lider losuje nowy klucz grupowy i osobno opakowuje
go dla każdego uczestnika kluczem wyprowadzonym z X25519 i HKDF. Serwer widzi
jedynie zaszyfrowane koperty.

## Budowanie

W katalogu głównym projektu:

```powershell
.\mvnw.cmd clean package
```

Maven zostanie pobrany automatycznie przy pierwszym uruchomieniu.

## Uruchomienie lokalne

Terminal 1 — serwer:

```powershell
java -jar server\target\server-0.1.0-SNAPSHOT.jar
```

Terminal 2 — pierwszy klient:

```powershell
java -jar client\target\CipherVoice.jar
```

Terminal 3 — drugi klient:

```powershell
java -jar client\target\CipherVoice.jar
```

Klient uruchamia graficzny ekran dołączania. Aby zakończyć, użyj przycisku
`Rozłącz` albo zamknij okno. Stan serwera jest dostępny pod
`http://localhost:8080/api/status`.

## Porty i test na dwóch komputerach

Serwer używa domyślnie TCP `8080` (HTTP), TCP `8090` (sterowanie) i UDP `8091`
(audio). W Zaporze Windows trzeba zezwolić Javie na ruch w sieci prywatnej.
Na drugim komputerze zamiast `localhost` należy podać lokalny adres IP serwera,
np. `192.168.1.20`.

## Model bezpieczeństwa prototypu

Prototyp chroni treść rozmowy przed pasywnym lub uczciwym, lecz ciekawskim
serwerem. Ponieważ użytkownicy nie mają kont ani wcześniej znanych kluczy
tożsamości, złośliwy serwer mógłby podmienić klucz publiczny i wykonać atak MITM.
W wersji docelowej należy dodać porównanie krótkiego kodu bezpieczeństwa (SAS),
kod QR albo inny mechanizm uwierzytelniania uczestników. To ograniczenie powinno
zostać jawnie opisane w pracy.

Obecna wersja jest MVP: nie ma jeszcze bufora jitter, miksowania kilku rozmówców,
GUI, NAT traversal ani zapisu konfiguracji. Te elementy można dodawać niezależnie
bez zmiany podstawowego modelu szyfrowania.
