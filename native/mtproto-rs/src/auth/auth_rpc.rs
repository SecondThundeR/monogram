//! Auth API methods (`auth.sendCode` / `signIn` / `checkPassword`).
//! https://core.telegram.org/api/auth
//! https://core.telegram.org/method/auth.sendCode
//! https://core.telegram.org/api/srp

use tellers_mtproto::latest::api::{
    AccountEmailVerified, AccountGetPasswordRequest, AccountPassword,
    AccountSendVerifyEmailCodeRequest, AccountSentEmailCode, AccountVerifyEmailRequest,
    AuthAuthorization, AuthCheckPasswordRequest, AuthCodeType, AuthLogOutRequest, AuthLoggedOut,
    AuthResendCodeRequest, AuthResetLoginEmailRequest, AuthSendCodeRequest, AuthSentCode,
    AuthSentCodeType, AuthSignInRequest, CodeSettings, CodeSettingsConstructor, EmailVerification,
    EmailVerificationCodeConstructor, EmailVerifyPurpose, EmailVerifyPurposeLoginSetupConstructor,
    InputCheckPasswordSrp, PasswordKdfAlgo, True, TrueConstructor, User, Vector, VectorConstructor,
};
use tellers_mtproto_session::Snapshot;

use crate::api_invoke;
use crate::auth::srp;
use crate::{AuthCodeSent, AuthCodeTypeDto, AuthSignedIn, AuthStepDto, MtprotoError};

fn code_settings(logout_tokens: &[Vec<u8>]) -> Box<CodeSettings> {
    let logout_tokens = (!logout_tokens.is_empty()).then(|| {
        Box::new(Vector::Vector(VectorConstructor {
            field_0: logout_tokens.len() as u32,
            field_1: logout_tokens.to_vec(),
        }))
    });
    let mut flags = CodeSettingsConstructor::ALLOW_APP_HASH_FLAG;
    if logout_tokens.is_some() {
        flags |= CodeSettingsConstructor::LOGOUT_TOKENS_FLAG;
    }
    Box::new(CodeSettings::CodeSettings(CodeSettingsConstructor {
        flags,
        allow_flashcall: None,
        current_number: None,
        allow_app_hash: Some(Box::new(True::True(TrueConstructor {}))),
        allow_missed_call: None,
        allow_firebase: None,
        unknown_number: None,
        logout_tokens,
        token: None,
        app_sandbox: None,
    }))
}

fn user_id(user: &User) -> Result<i64, MtprotoError> {
    match user {
        User::User(u) => Ok(u.id),
        User::UserEmpty(u) => Ok(u.id),
        _ => Err(MtprotoError::Message("unexpected user constructor".into())),
    }
}

fn authorization_to_signed(
    auth: AuthAuthorization,
    dc_id: i32,
    tokens: &mut Vec<Vec<u8>>,
) -> Result<AuthSignedIn, MtprotoError> {
    match auth {
        AuthAuthorization::AuthAuthorization(a) => {
            let signed = AuthSignedIn {
                user_id: user_id(&a.user)?,
                dc_id,
            };
            remember_token(tokens, a.future_auth_token);
            Ok(signed)
        }
        AuthAuthorization::AuthAuthorizationSignUpRequired(_) => {
            Err(MtprotoError::RegistrationRequired)
        }
        _ => Err(MtprotoError::Message("unexpected authorization".into())),
    }
}

/// Manual test accounts: `99966XYYYY` on test DCs (X = 1..3).
/// https://core.telegram.org/api/obtaining_api_id#test-accounts
pub fn test_dc_from_phone(phone: &str) -> Option<i32> {
    let digits: String = phone.chars().filter(|c| c.is_ascii_digit()).collect();
    if digits.len() != 10 || !digits.starts_with("99966") {
        return None;
    }
    let dc = (digits.as_bytes()[5] - b'0') as i32;
    (1..=3).contains(&dc).then_some(dc)
}

pub fn send_code(
    snapshot: &mut Snapshot,
    api_id: i32,
    api_hash: &str,
    phone: &str,
    logout_tokens: &mut Vec<Vec<u8>>,
) -> Result<AuthStepDto, MtprotoError> {
    let phone = phone.to_string();
    let sent: AuthSentCode = api_invoke::invoke_api(
        snapshot,
        api_id,
        AuthSendCodeRequest {
            phone_number: phone.clone(),
            api_id,
            api_hash: api_hash.into(),
            settings: code_settings(logout_tokens),
        },
    )?;
    sent_code_to_step(phone, sent, snapshot.dc_id, logout_tokens)
}

/// https://core.telegram.org/method/auth.resendCode
pub fn resend_code(
    snapshot: &mut Snapshot,
    api_id: i32,
    phone: &str,
    phone_code_hash: &str,
    tokens: &mut Vec<Vec<u8>>,
) -> Result<AuthStepDto, MtprotoError> {
    let phone = phone.to_string();
    let sent: AuthSentCode = api_invoke::invoke_api(
        snapshot,
        api_id,
        AuthResendCodeRequest {
            flags: 0,
            phone_number: phone.clone(),
            phone_code_hash: phone_code_hash.into(),
            reason: None,
        },
    )?;
    sent_code_to_step(phone, sent, snapshot.dc_id, tokens)
}

pub(crate) fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs() as i64
}

pub(crate) fn remember_token(tokens: &mut Vec<Vec<u8>>, token: Option<Vec<u8>>) {
    if let Some(token) = token.filter(|t| !t.is_empty() && t.len() <= 256) {
        tokens.retain(|saved| saved != &token);
        tokens.push(token);
        if tokens.len() > 20 {
            tokens.drain(..tokens.len() - 20);
        }
    }
}

fn sent_code_to_step(
    phone: String,
    sent: AuthSentCode,
    dc_id: i32,
    tokens: &mut Vec<Vec<u8>>,
) -> Result<AuthStepDto, MtprotoError> {
    let s = match sent {
        AuthSentCode::AuthSentCode(s) => s,
        AuthSentCode::AuthSentCodeSuccess(s) => {
            return Ok(AuthStepDto::Authorized {
                signed: authorization_to_signed(*s.authorization, dc_id, tokens)?,
            });
        }
        _ => {
            return Err(MtprotoError::Message(
                "unsupported authorization response".into(),
            ));
        }
    };
    let mut sent = AuthCodeSent {
        phone,
        phone_code_hash: s.phone_code_hash,
        next_type: s.next_type.as_deref().map(|t| match t {
            AuthCodeType::AuthCodeTypeSms(_) => AuthCodeTypeDto::Sms,
            AuthCodeType::AuthCodeTypeCall(_) => AuthCodeTypeDto::Call,
            AuthCodeType::AuthCodeTypeFlashCall(_) => AuthCodeTypeDto::FlashCall,
            AuthCodeType::AuthCodeTypeMissedCall(_) => AuthCodeTypeDto::MissedCall,
            AuthCodeType::AuthCodeTypeFragmentSms(_) => AuthCodeTypeDto::Fragment,
        }),
        resend_available_at: now() + i64::from(s.timeout.unwrap_or(0).max(0)),
        code_length: 5,
        ..Default::default()
    };
    match *s.type_ {
        AuthSentCodeType::AuthSentCodeTypeApp(t) => {
            sent.code_type = AuthCodeTypeDto::App;
            sent.code_length = t.length;
        }
        AuthSentCodeType::AuthSentCodeTypeSms(t) => {
            sent.code_type = AuthCodeTypeDto::Sms;
            sent.code_length = t.length;
        }
        AuthSentCodeType::AuthSentCodeTypeCall(t) => {
            sent.code_type = AuthCodeTypeDto::Call;
            sent.code_length = t.length;
        }
        AuthSentCodeType::AuthSentCodeTypeFlashCall(t) => {
            sent.code_type = AuthCodeTypeDto::FlashCall;
            sent.phone_pattern = t.pattern;
            sent.code_length = 0;
        }
        AuthSentCodeType::AuthSentCodeTypeMissedCall(t) => {
            sent.code_type = AuthCodeTypeDto::MissedCall;
            sent.phone_pattern = t.prefix;
            sent.code_length = t.length;
        }
        AuthSentCodeType::AuthSentCodeTypeEmailCode(t) => {
            sent.code_type = AuthCodeTypeDto::Email;
            sent.resend_available_at = now() + i64::from(s.timeout.unwrap_or(60).max(0));
            sent.code_length = t.length;
            sent.email_pattern = t.email_pattern;
            sent.email_reset_available_at = t
                .reset_available_period
                .map(|delay| now() + i64::from(delay.max(0)));
            sent.email_reset_pending_date = t.reset_pending_date.filter(|date| *date > 0).map(i64::from);
            sent.google_signin_allowed = t.google_signin_allowed.is_some();
            sent.apple_signin_allowed = t.apple_signin_allowed.is_some();
        }
        AuthSentCodeType::AuthSentCodeTypeSetUpEmailRequired(t) => {
            sent.code_type = AuthCodeTypeDto::EmailSetup;
            sent.google_signin_allowed = t.google_signin_allowed.is_some();
            sent.apple_signin_allowed = t.apple_signin_allowed.is_some();
        }
        AuthSentCodeType::AuthSentCodeTypeFragmentSms(t) => {
            sent.code_type = AuthCodeTypeDto::Fragment;
            sent.code_length = t.length;
            sent.fragment_url = t.url;
        }
        AuthSentCodeType::AuthSentCodeTypeFirebaseSms(t) => {
            sent.code_type = AuthCodeTypeDto::Firebase;
            sent.code_length = t.length;
        }
        AuthSentCodeType::AuthSentCodeTypeSmsWord(t) => {
            sent.code_type = AuthCodeTypeDto::SmsWord;
            sent.code_length = 0;
            sent.beginning = t.beginning.unwrap_or_default();
        }
        AuthSentCodeType::AuthSentCodeTypeSmsPhrase(t) => {
            sent.code_type = AuthCodeTypeDto::SmsPhrase;
            sent.code_length = 0;
            sent.beginning = t.beginning.unwrap_or_default();
        }
    }
    if sent.code_length <= 0
        && !matches!(
            sent.code_type,
            AuthCodeTypeDto::SmsWord | AuthCodeTypeDto::SmsPhrase | AuthCodeTypeDto::FlashCall
        )
    {
        sent.code_length = 5;
    }
    Ok(AuthStepDto::AwaitingCode { sent })
}

fn email_purpose(phone: &str, hash: &str) -> Box<EmailVerifyPurpose> {
    Box::new(EmailVerifyPurpose::EmailVerifyPurposeLoginSetup(
        EmailVerifyPurposeLoginSetupConstructor {
            phone_number: phone.into(),
            phone_code_hash: hash.into(),
        },
    ))
}

pub fn send_email(
    snapshot: &mut Snapshot,
    api_id: i32,
    phone: &str,
    hash: &str,
    email: &str,
) -> Result<AuthStepDto, MtprotoError> {
    let sent: AccountSentEmailCode =
        api_invoke::invoke_api(snapshot, api_id, email_code_request(phone, hash, email))?;
    Ok(email_code_to_step(phone, hash, email, sent))
}

fn email_code_request(phone: &str, hash: &str, email: &str) -> AccountSendVerifyEmailCodeRequest {
    AccountSendVerifyEmailCodeRequest {
        purpose: email_purpose(phone, hash),
        email: email.into(),
    }
}

fn email_code_to_step(
    phone: &str,
    hash: &str,
    email: &str,
    sent: AccountSentEmailCode,
) -> AuthStepDto {
    let AccountSentEmailCode::AccountSentEmailCode(sent) = sent;
    AuthStepDto::AwaitingCode {
        sent: AuthCodeSent {
            phone: phone.into(),
            phone_code_hash: hash.into(),
            code_type: if email.is_empty() {
                AuthCodeTypeDto::Email
            } else {
                AuthCodeTypeDto::EmailSetupCode
            },
            code_length: if sent.length > 0 { sent.length } else { 5 },
            email_pattern: sent.email_pattern,
            email_address: (!email.is_empty()).then(|| email.into()),
            // account.sentEmailCode does not provide a retry timeout.
            resend_available_at: now() + 60,
            ..Default::default()
        },
    }
}

pub fn verify_email(
    snapshot: &mut Snapshot,
    api_id: i32,
    phone: &str,
    hash: &str,
    code: &str,
    tokens: &mut Vec<Vec<u8>>,
) -> Result<AuthStepDto, MtprotoError> {
    let verified: AccountEmailVerified = api_invoke::invoke_api(
        snapshot,
        api_id,
        AccountVerifyEmailRequest {
            purpose: email_purpose(phone, hash),
            verification: Box::new(EmailVerification::EmailVerificationCode(
                EmailVerificationCodeConstructor { code: code.into() },
            )),
        },
    )?;
    match verified {
        AccountEmailVerified::AccountEmailVerifiedLogin(verified) => {
            sent_code_to_step(phone.into(), *verified.sent_code, snapshot.dc_id, tokens)
        }
        _ => Err(MtprotoError::Message(
            "unexpected email verification response".into(),
        )),
    }
}

pub fn reset_email(
    snapshot: &mut Snapshot,
    api_id: i32,
    phone: &str,
    hash: &str,
    tokens: &mut Vec<Vec<u8>>,
) -> Result<AuthStepDto, MtprotoError> {
    let sent: AuthSentCode = api_invoke::invoke_api(
        snapshot,
        api_id,
        AuthResetLoginEmailRequest {
            phone_number: phone.into(),
            phone_code_hash: hash.into(),
        },
    )?;
    sent_code_to_step(phone.into(), sent, snapshot.dc_id, tokens)
}

fn sign_in_request(phone: &str, hash: &str, code: &str, email: bool) -> AuthSignInRequest {
    AuthSignInRequest {
        flags: if email { 2 } else { 1 },
        phone_number: phone.into(),
        phone_code_hash: hash.into(),
        phone_code: (!email).then(|| code.into()),
        email_verification: email.then(|| {
            Box::new(EmailVerification::EmailVerificationCode(
                EmailVerificationCodeConstructor { code: code.into() },
            ))
        }),
    }
}

pub fn sign_in(
    snapshot: &mut Snapshot,
    api_id: i32,
    phone: &str,
    hash: &str,
    code: &str,
    email: bool,
    tokens: &mut Vec<Vec<u8>>,
) -> Result<AuthSignedIn, MtprotoError> {
    let auth: AuthAuthorization =
        api_invoke::invoke_api(snapshot, api_id, sign_in_request(phone, hash, code, email))?;
    authorization_to_signed(auth, snapshot.dc_id, tokens)
}

pub(crate) fn is_srp_id_invalid(err: &MtprotoError) -> bool {
    matches!(err, MtprotoError::Message(message) if message.contains("SRP_ID_INVALID"))
}

/// `account.getPassword` then `auth.checkPassword`. Retry once on `SRP_ID_INVALID`
/// (srp_id is short-lived; https://core.telegram.org/api/srp).
pub fn check_password(
    snapshot: &mut Snapshot,
    api_id: i32,
    password: &str,
    tokens: &mut Vec<Vec<u8>>,
) -> Result<AuthSignedIn, MtprotoError> {
    match check_password_once(snapshot, api_id, password, tokens) {
        Ok(signed) => Ok(signed),
        Err(err) if is_srp_id_invalid(&err) => {
            check_password_once(snapshot, api_id, password, tokens)
        }
        Err(err) => Err(err),
    }
}

/// Revoke the current authorization on Telegram. Local teardown is owned by
/// the client manager even when this RPC fails because the key was already
/// revoked or the connection is unavailable.
pub fn log_out(snapshot: &mut Snapshot, api_id: i32) -> Result<Option<Vec<u8>>, MtprotoError> {
    let logged: AuthLoggedOut = api_invoke::invoke_api(snapshot, api_id, AuthLogOutRequest {})?;
    let AuthLoggedOut::AuthLoggedOut(logged) = logged;
    Ok(logged.future_auth_token)
}

fn check_password_once(
    snapshot: &mut Snapshot,
    api_id: i32,
    password: &str,
    tokens: &mut Vec<Vec<u8>>,
) -> Result<AuthSignedIn, MtprotoError> {
    let pwd: AccountPassword =
        api_invoke::invoke_api(snapshot, api_id, AccountGetPasswordRequest {})?;
    let AccountPassword::AccountPassword(pwd) = pwd else {
        return Err(MtprotoError::Message("unexpected account.password".into()));
    };
    let Some(algo) = pwd.current_algo else {
        return Err(MtprotoError::Message("no current password algo".into()));
    };
    let PasswordKdfAlgo::PasswordKdfAlgoSha256sha256pbkdf2hmacsha512iter100000Sha256ModPow(algo) =
        *algo
    else {
        return Err(MtprotoError::Message(
            "unsupported password KDF (client outdated?)".into(),
        ));
    };
    let srp_b = pwd
        .srp_b
        .ok_or_else(|| MtprotoError::Message("missing srp_B".into()))?;
    let srp_id = pwd
        .srp_id
        .ok_or_else(|| MtprotoError::Message("missing srp_id".into()))?;
    let srp_input: InputCheckPasswordSrp =
        srp::input_check_password(password, &algo, &srp_b, srp_id)?;
    let auth: AuthAuthorization = api_invoke::invoke_api(
        snapshot,
        api_id,
        AuthCheckPasswordRequest {
            password: Box::new(srp_input),
        },
    )?;
    authorization_to_signed(auth, snapshot.dc_id, tokens)
}

#[cfg(test)]
#[path = "../../tests/unit/auth_auth_rpc_tests.rs"]
mod tests;
