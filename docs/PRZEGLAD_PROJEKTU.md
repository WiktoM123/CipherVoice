# Przegląd projektu CipherVoice

Data: 2 listopada 2026.

Przegląd objął kod modułów client, server i common, konfigurację Mavena,
wrappery, sposób uruchamiania oraz dokumentację. Uporządkowano pakiety
i poprawiono konkretne błędy ujawnione przy analizie. Jest to weryfikacja
prototypu na podstawie kodu i testów, a nie certyfikacja jego bezpieczeństwa.

Połączono też lokalne zmiany interfejsu z trzema nowszymi commitami GitHuba.
Aktualna wersja zachowuje protokół v3, losowe nonce AES-GCM, limity użycia
klucza, miksowanie głosów, testy integracyjne i lokalny wybór urządzeń audio.

## Struktura i uruchamianie

Wspólny prefiks pakietów to `pl.ciphervoice`. Przykładowa ścieżka serwera:

```text
server/src/main/java/pl/ciphervoice/server/ControlServer.java
```

`src/main/java` jest standardowym katalogiem źródeł Mavena. Zmieniono
deklaracje pakietów, importy, lokalizację testów, groupId oraz klasy startowe.
Domyślne porty pozostały bez zmian. Po scaleniu obowiązuje format pakietów
UDP protokołu v3; klient i serwer muszą używać zgodnej wersji. Losowy nonce
ma 12 bajtów i jest częścią uwierzytelnianego nagłówka.

Budowanie w katalogu PRACA:

```powershell
.\mvnw.cmd clean verify
```

Serwer i klient w dwóch oddzielnych terminalach:

```powershell
.\start-server.cmd
.\start-client.cmd
```

Skrypty odnajdują JAR-y względem własnego katalogu, obsługują `JAVA_HOME`
i Javę z `PATH`, a także przekazują argumenty oraz kody zakończenia.
Poprzedni wrapper Windows mógł odrzucać poprawną Javę z PATH i zwracać
kod sukcesu mimo błędu Mavena.

## Poprawione błędy

| Problem | Zastosowana poprawka |
|---|---|
| Klucz, epoka i resetowany licznik mogły pochodzić z różnych chwil | Osobny kontekst kryptograficzny epoki z własnym kluczem i licznikiem |
| Powtórna koperta klucza zerowała licznik AES-GCM | Ignorowanie ponownej instalacji klucza aktywnej epoki |
| Stara ramka mogła nadpisać ochronę przed powtórzeniami nowej epoki | Osobny stan odbioru i sprawdzenie aktualności kontekstu przed zatwierdzeniem |
| Starszy stan pokoju mógł cofnąć klienta do poprzedniej epoki | Ignorowanie starszych epok i uporządkowanie publikacji po stronie serwera |
| Spóźniona koperta lub odejście odbiorcy mogły rozłączyć lidera | Atomowa kwalifikacja odbiorcy; pomijanie nieaktualnej koperty; błąd zapisu przypisany odbiorcy |
| Nieprawidłowy klucz publiczny uczestnika mógł przerwać rekey innych osób | Walidacja Base64, DER, typu X25519 i użyteczności klucza przed JOIN |
| Zerwanie TCP nie zamykało mikrofonu i UDP | Wspólne, idempotentne sprzątanie po EOF, błędzie startu i ręcznym rozłączeniu |
| Błąd rejestracji UDP omijał opóźnienie ponowienia | Opóźnienie obowiązuje również po nieudanej próbie |
| Częściowo uruchomione audio zostawiało otwarte urządzenia | Wycofanie uruchamiania i zamykanie już otwartych linii |
| Oczekiwanie na opróżnienie głośnika mogło blokować rozłączanie | Zatrzymanie i opróżnienie bufora bez blokującego drain |
| Zamknięcie serwera mogło czekać na bezczynnych klientów | Ewidencja socketów, ich zamknięcie i zakończenie wykonawcy |
| Zajęty port nie przerywał startu aplikacji | Wiązanie TCP/UDP podczas inicjalizacji komponentów Spring |
| Diagnostyka zwracała port 0 zamiast przydzielonego portu | Endpoint statusu odczytuje rzeczywiście związane porty TCP/UDP |
| Odczyt TCP nie miał ograniczenia długości | Czytnik ramek do 16 KiB oraz timeout bezczynności JOIN |
| Awaryjne zamykanie zatrzymywało dowolny proces na portach aplikacji | Weryfikacja pełnych ścieżek JAR/EXE tej kopii projektu; tryb podglądu `-WhatIf` |

Usunięto też nieużywaną zależność Jackson z common oraz mylące, nieaktywne
ustawienie wersji JUnit. Zależności testowe nie są dołączane do gotowego
klienta. Katalogi target, dist i robocze pliki prezentacji są traktowane jako
wygenerowane materiały, bez usuwania istniejących prezentacji.

## Wyniki weryfikacji

- Bazowy projekt przed zmianami: 2 testy, wszystkie poprawne.
- Pierwszy przegląd zakończył się 35 poprawnymi testami.
- Po scaleniu i uzgodnieniu walidacji portu 0: `mvnw.cmd verify` zakończyło się `BUILD SUCCESS`.
- Common: 6 testów, bez błędów i pominięć.
- Klient: 29 testów, bez błędów i pominięć.
- Serwer: 31 testów, w tym 4 testy integracyjne z prawdziwymi klientami TCP/UDP; bez błędów i pominięć.
- Łącznie: 66 testów JUnit.
- Wrappery i skrypty startowe: 22 scenariusze z atrapą Javy, wszystkie poprawne, w tym ścieżki ze spacjami i propagacja kodu błędu.
- Wybór procesów do zamknięcia: 6 scenariuszy, bez zatrzymywania rzeczywistych procesów.
- Gotowy JAR klienta: prawidłowy Main-Class `pl.ciphervoice.client.ClientMain`, brak plików starego pakietu i klas Mockito.

Testy jednostkowe klienta korzystają z atrap, a integracyjne wyłączają audio,
aby nie otwierać mikrofonu ani głośników. Testy serwera wykorzystują atrapy
oraz porty przydzielane przez system,
bez uruchamiania serwera na domyślnych portach użytkownika.

Przed scaleniem osobny test na gotowym JAR-ze serwera sprawdził dwa rzeczywiste połączenia
TCP, uzgodnienie X25519, przekazanie i odszyfrowanie koperty klucza, rejestrację
UDP oraz przekazanie zaszyfrowanych datagramów w obu kierunkach. Sprawdzono
zmianę lidera i epoki po odejściu uczestnika oraz usunięcie pustego pokoju.
Ponowne wykonanie testu na końcowych JAR-ach potwierdziło też zgodność
portów zwracanych przez HTTP z portami TCP/UDP przydzielonymi przez system.
Użyto syntetycznej zawartości pakietów, bez kodowania Opus i bez urządzeń
audio. Test potwierdza przepływ protokołu, a nie jakość rozmowy głosowej.

Istniejący pakiet `dist/CipherVoice` otrzymał nowy JAR i poprawione nazwy
klasy startowej. Poprzednie pliki JAR, CFG i metadane jpackage zachowano w
`../tmp/ciphervoice_dist_before_refactor`. Nie uruchamiano interfejsu EXE
ani fizycznych urządzeń audio podczas tej aktualizacji.

## Ograniczenia istotne dla pracy inżynierskiej

- Weryfikacja kluczy opiera się na lokalnym sześciocyfrowym SAS porównywanym przez użytkowników. Nie ma commit-reveal ani trwałych tożsamości, więc nie należy deklarować automatycznej odporności na aktywny serwer podmieniający klucze: SAS wykrywa podmianę tylko po faktycznym porównaniu kodu. Model opisany w README nadal zakłada serwer działający zgodnie z protokołem do chwili weryfikacji.
- Kolejki odtwarzania mają stały rozmiar do trzech ramek; nie jest to adaptacyjny bufor jitter. Miksowanie dodaje próbki z nasyceniem. Jakość rozmowy wielu osób wymaga rzeczywistego testu audio; samo przekazanie datagramów jej nie potwierdza.
- Zmiana urządzeń audio wymaga ponownego dołączenia. Brak NAT traversal, kont i kontroli dostępu do pokojów.
- Limit długości ramek i timeout bezczynności nie zapewniają pełnej odporności na DoS. Nie ma limitu liczby połączeń, rate limitu ani deadline zapisu do klienta.

Przed opisaniem wyników w pracy wykonaj test na co najmniej dwóch komputerach
ze słuchawkami. Sprawdź rozmowę dwóch osób, jednoczesne mówienie kilku osób,
dołączanie i opuszczanie pokoju, zmianę lidera, odłączenie sieci i ponowne
uruchomienie serwera. Zmierz opóźnienie audio, straty pakietów i zachowanie
przy zmiennych opóźnieniach. Wyniki tych eksperymentów trzeba uzyskać osobno.

## Aktualizacja 24.11.2026: weryfikacja SAS

Dodano lokalną weryfikację klucza grupowego sześciocyfrowym kodem SAS.
Nowa klasa `SasVerification` wyprowadza kod przez HKDF-SHA-256 z klucza
grupowego oraz jednoznacznego transkryptu: wersja, pokój, epoka, lider i wszyscy
uczestnicy posortowani po UUID. Każdy wpis zawiera `clientId`, nickname, klucz
publiczny X25519 i `senderTag`. Kod jest wyświetlany w nagłówku pokoju oraz
w oknie weryfikacji; użytkownik potwierdza zgodność lokalnie przyciskiem.
SAS i status weryfikacji nie są wysyłane przez serwer i nie zmieniają protokołu
TCP/UDP. Zmiana klucza, epoki, lidera lub składu pokoju resetuje status.

Wyniki po tej zmianie:

- `SasVerificationTest`: 6 testów jednostkowych — stały wektor, niezależność od
  kolejności listy, zmiana klucza/epoki/pokoju/lidera, zmiana pól uczestnika,
  zmiana składu oraz odrzucanie niepełnych i dwuznacznych danych.
- Test integracyjny z prawdziwym serwerem i dwoma klientami sprawdza, że obaj
  widzą ten sam SAS, że potwierdzenie jednego klienta nie zmienia stanu
  drugiego i że po odejściu uczestnika kod oraz status weryfikacji się resetują.
- `mvnw.cmd test`: 81 testów, 0 błędów i 0 pominięć (common 6, klient 43,
  serwer 32, w tym 4 testy integracyjne).

Ograniczenia SAS: sześć cyfr to około 20 bitów (szansa przypadkowej zgodności
około 1 na 1 000 000), jest to jeden kod dla całego pokoju, brak commit-reveal
i trwałych kluczy tożsamości. Ochrona działa tylko wtedy, gdy użytkownicy
naprawdę porównają kod poza kanałem.
