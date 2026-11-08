import json
import os
import smtplib
import ssl
import subprocess
import logging
from email.message import EmailMessage
from html import escape


FINANCE_EMAIL = "yishengzaidu@qq.com"
logger = logging.getLogger("email-notifier")


def smtp_credentials():
    """SMTP 凭据放在 /etc/spxchat/secrets.php；环境变量优先，源码里不再保留口令。"""
    cached = getattr(smtp_credentials, "_cache", None)
    if cached is not None:
        return cached
    user = os.getenv("FISS_SMTP_USER", "")
    password = os.getenv("FISS_SMTP_PASS", "")
    if not (user and password):
        try:
            result = subprocess.run(
                ["php", "-r", '$s = require "/etc/spxchat/secrets.php"; echo json_encode($s["smtp"] ?? []);'],
                capture_output=True, text=True, timeout=10,
            )
            if result.returncode == 0 and result.stdout.strip():
                data = json.loads(result.stdout)
                user = user or str(data.get("user") or "")
                password = password or str(data.get("pass") or "")
        except Exception as exc:
            logger.warning("读取 /etc/spxchat/secrets.php 失败: %s", exc)
    cached = (user, password)
    smtp_credentials._cache = cached
    return cached


def send_finance_email_to(recipient, subject, html):
    user, password = smtp_credentials()
    host = os.getenv("FISS_SMTP_HOST", "smtp.qq.com")
    port = int(os.getenv("FISS_SMTP_PORT", "465"))
    if not password:
        raise RuntimeError("未配置 SMTP 口令（/etc/spxchat/secrets.php 或 FISS_SMTP_PASS）")
    message = EmailMessage()
    message["From"] = f"SPX Chat <{user}>"
    message["To"] = recipient
    message["Subject"] = subject
    message.set_content("请使用支持 HTML 的邮件客户端查看此通知。")
    message.add_alternative(html, subtype="html")
    context = ssl.create_default_context()
    with smtplib.SMTP_SSL(host, port, context=context, timeout=15) as server:
        server.login(user, password)
        server.send_message(message)


def send_finance_email(subject, html):
    send_finance_email_to(FINANCE_EMAIL, subject, html)


def send_finance_template(connection, event_key, variables):
    with connection.cursor() as cursor:
        cursor.execute(
            "SELECT id,event_key,subject,body_html FROM email_notification_templates "
            "WHERE event_key=%s AND status=1 LIMIT 1",
            (event_key,),
        )
        template = cursor.fetchone()
        if not template:
            return
        cursor.execute(
            "SELECT DISTINCT r.email FROM email_notification_recipients r "
            "JOIN email_notification_subscriptions s ON s.recipient_id=r.id "
            "WHERE s.template_id=%s AND r.status=1",
            (template["id"],),
        )
        recipients = cursor.fetchall()
    subject = str(template["subject"])
    body = str(template["body_html"])
    for key, value in variables.items():
        subject = subject.replace("{{" + key + "}}", str(value))
        body = body.replace("{{" + key + "}}", html_text(value))
    for recipient in recipients:
        email = str(recipient["email"])
        status = "sent"
        error_message = None
        try:
            send_finance_email_to(email, subject, body)
        except Exception as exc:
            status = "failed"
            error_message = str(exc)[:500]
            logger.exception("财务邮件发送失败: %s", email)
        with connection.cursor() as cursor:
            cursor.execute(
                "INSERT INTO email_notification_logs "
                "(event_key,recipient_email,subject,status,error_message) VALUES (%s,%s,%s,%s,%s)",
                (event_key, email, subject, status, error_message),
            )
        connection.commit()


def html_text(value):
    return escape(str(value if value is not None else ""))
