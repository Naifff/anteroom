package org.anteroom;

/**
 * Отказ, который увидит человек.
 *
 * <p>Лежит в корневом пакете, а не в {@code ws}: коды бросают и сервисы комнат, и файлы,
 * и личные сообщения. Положить его рядом с сокетом значило бы завернуть зависимость
 * наоборот — от предметной области к транспорту.
 *
 * <p>Наружу уходит <b>код</b>, а не фраза. Причина не в красоте: интерфейс переводится, а
 * строка, собранная на сервере, переводу не поддаётся — язык выбирают в браузере, и сервер
 * о нём не знает. Раньше клиент ещё и сравнивал отказ с текстом
 * ({@code reason === 'нужно приглашение'}); такая ветка перестаёт срабатывать от правки
 * формулировки, причём молча.
 *
 * <p>Наследуется от {@link IllegalArgumentException} намеренно: обработчик уже ловит его на
 * границе, и всё, что бросают сервисы без своего кода, продолжает работать — просто
 * показывается как {@code unknown}, а подробность остаётся в журнале сервера, где ей и место.
 *
 * <p><b>Коды — часть протокола.</b> Каждый должен быть заведён в словаре
 * {@code static/js/i18n.js} под ключом {@code e.<код>}. Незнакомый код браузер покажет как
 * есть — уродливо и заметно, а не пусто.
 */
public class Refusal extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** Нужно приглашение: подпись говорит «тот же ключ», а не «его сюда звали». */
    public static final String INVITE_REQUIRED = "invite-required";
    /** Комната раздала все 52 карты. Конец жизни комнаты, а не ошибка выдачи. */
    public static final String DECK_SPENT = "deck-spent";
    /** Нет такой комнаты либо вы не участник — снаружи это один и тот же ответ. */
    public static final String NO_ACCESS = "no-access";
    public static final String SELF_REMOVE = "self-remove";
    public static final String WORK_REJECTED = "work-rejected";
    public static final String TOO_OFTEN = "too-often";
    public static final String BAD_FRAME = "bad-frame";
    public static final String UNKNOWN_OP = "unknown-op";
    /** Истёкшее, исчерпанное и несуществующее приглашение отвечают одинаково. */
    public static final String INVITE_INVALID = "invite-invalid";
    public static final String FILE_TOO_LARGE = "file-too-large";
    public static final String ROOM_FULL = "room-full";
    public static final String DISK_FULL = "disk-full";
    public static final String UPLOAD_REFUSED = "upload-refused";
    public static final String DM_DISABLED = "dm-disabled";
    public static final String BAD_ENVELOPES = "bad-envelopes";
    public static final String MESSAGE_TOO_LARGE = "message-too-large";
    public static final String NOT_A_MEMBER = "not-a-member";
    public static final String TTL_TOO_SHORT = "ttl-too-short";
    /** Всё, у чего своего кода нет. */
    public static final String UNKNOWN = "unknown";

    private final String code;

    public Refusal(String code) {
        // Сообщение = код: в журнале сервера видно, чем именно отказали, без второго поля.
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
