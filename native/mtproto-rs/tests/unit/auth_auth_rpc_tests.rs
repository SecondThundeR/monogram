use super::{code_settings, is_srp_id_invalid, test_dc_from_phone};
use crate::MtprotoError;
use tellers_mtproto::latest::api::{CodeSettings, CodeSettingsConstructor, Vector};

#[test]
fn detects_stale_srp_id() {
    assert!(is_srp_id_invalid(&MtprotoError::Message(
        "RPC 400: SRP_ID_INVALID".into(),
    )));
    assert!(!is_srp_id_invalid(&MtprotoError::Message(
        "RPC 400: PASSWORD_HASH_INVALID".into(),
    )));
}

#[test]
fn test_phone_maps_to_test_dc() {
    assert_eq!(test_dc_from_phone("+9996621234"), Some(2));
    assert_eq!(test_dc_from_phone("9996610000"), Some(1));
    assert_eq!(test_dc_from_phone("9996639999"), Some(3));
    assert_eq!(test_dc_from_phone("9996640000"), None);
    assert_eq!(test_dc_from_phone("+79180705210"), None);
    assert_eq!(test_dc_from_phone("99966"), None);
}

#[test]
fn code_settings_requests_sms_app_hash() {
    let settings = code_settings(&[]);
    let CodeSettings::CodeSettings(settings) = *settings;
    assert_eq!(settings.flags, CodeSettingsConstructor::ALLOW_APP_HASH_FLAG);
    assert!(settings.allow_app_hash.is_some());
    assert!(settings.logout_tokens.is_none());
}

#[test]
fn code_settings_carries_bounded_logout_tokens() {
    let settings = code_settings(&[vec![1, 2, 3]]);
    let CodeSettings::CodeSettings(settings) = *settings;
    assert_eq!(
        settings.flags,
        CodeSettingsConstructor::ALLOW_APP_HASH_FLAG | CodeSettingsConstructor::LOGOUT_TOKENS_FLAG,
    );
    assert!(settings.allow_app_hash.is_some());
    let Some(tokens) = settings.logout_tokens else {
        panic!("logout tokens missing");
    };
    let Vector::Vector(tokens) = *tokens;
    assert_eq!(tokens.field_0, 1);
    assert_eq!(tokens.field_1, vec![vec![1, 2, 3]]);
}

#[test]
fn sent_code_preserves_delivery_and_retry_metadata() {
    use super::{now, sent_code_to_step};
    use crate::{AuthCodeTypeDto, AuthStepDto};
    use tellers_mtproto::latest::api::*;
    let before = now();
    let step = sent_code_to_step(
        "+12025550123".into(),
        AuthSentCode::AuthSentCode(AuthSentCodeConstructor {
            flags: 6,
            type_: Box::new(AuthSentCodeType::AuthSentCodeTypeEmailCode(
                AuthSentCodeTypeEmailCodeConstructor {
                    flags: 8,
                    apple_signin_allowed: None,
                    google_signin_allowed: None,
                    email_pattern: "a***@example.com".into(),
                    length: 6,
                    reset_available_period: Some(30),
                    reset_pending_date: None,
                },
            )),
            phone_code_hash: "new-hash".into(),
            next_type: Some(Box::new(AuthCodeType::AuthCodeTypeSms(
                AuthCodeTypeSmsConstructor {},
            ))),
            timeout: Some(60),
        }),
        2,
        &mut vec![],
    )
    .unwrap();
    let AuthStepDto::AwaitingCode { sent } = step else {
        panic!("expected code")
    };
    assert_eq!(sent.code_type, AuthCodeTypeDto::Email);
    assert_eq!(sent.code_length, 6);
    assert_eq!(sent.email_pattern, "a***@example.com");
    assert_eq!(sent.phone_code_hash, "new-hash");
    assert_eq!(sent.next_type, Some(AuthCodeTypeDto::Sms));
    assert!(sent.resend_available_at >= before + 60);
    assert!(sent.email_reset_available_at.unwrap() >= before + 30);
}

#[test]
fn email_sign_in_uses_email_verification_instead_of_phone_code() {
    use super::sign_in_request;
    use tellers_mtproto::latest::api::EmailVerification;
    let request = sign_in_request("+12025550123", "hash", "123456", true);
    assert_eq!(request.flags, 2);
    assert!(request.phone_code.is_none());
    let EmailVerification::EmailVerificationCode(code) = *request.email_verification.unwrap()
    else {
        panic!("expected email code")
    };
    assert_eq!(code.code, "123456");
    let phone = sign_in_request("+12025550123", "hash", "123456", false);
    assert_eq!(phone.flags, 1);
    assert_eq!(phone.phone_code.as_deref(), Some("123456"));
    assert!(phone.email_verification.is_none());
}

#[test]
fn sent_code_success_authorizes_and_remembers_future_token() {
    use super::sent_code_to_step;
    use crate::{AuthSignedIn, AuthStepDto};
    use tellers_mtproto::latest::api::*;
    let mut tokens = vec![];
    let step = sent_code_to_step(
        "+12025550123".into(),
        AuthSentCode::AuthSentCodeSuccess(AuthSentCodeSuccessConstructor {
            authorization: Box::new(AuthAuthorization::AuthAuthorization(
                AuthAuthorizationConstructor {
                    flags: 4,
                    setup_password_required: None,
                    otherwise_relogin_days: None,
                    tmp_sessions: None,
                    future_auth_token: Some(vec![7; 16]),
                    user: Box::new(User::UserEmpty(UserEmptyConstructor { id: 42 })),
                },
            )),
        }),
        4,
        &mut tokens,
    )
    .unwrap();
    assert_eq!(
        step,
        AuthStepDto::Authorized {
            signed: AuthSignedIn {
                user_id: 42,
                dc_id: 4
            }
        }
    );
    assert_eq!(tokens, vec![vec![7; 16]]);
}

#[test]
fn future_tokens_are_deduplicated_bounded_and_reject_invalid_values() {
    use super::remember_token;
    let mut tokens = vec![];
    for value in 0..25 {
        remember_token(&mut tokens, Some(vec![value]));
    }
    assert_eq!(tokens.len(), 20);
    assert_eq!(tokens[0], vec![5]);
    remember_token(&mut tokens, Some(vec![5]));
    assert_eq!(tokens.last(), Some(&vec![5]));
    remember_token(&mut tokens, Some(vec![]));
    remember_token(&mut tokens, Some(vec![1; 257]));
    assert_eq!(tokens.len(), 20);
}

#[test]
fn existing_email_resend_stays_on_login_verification() {
    use super::{email_code_request, email_code_to_step};
    use crate::{AuthCodeTypeDto, AuthStepDto};
    use tellers_mtproto::latest::api::*;
    let request = email_code_request("+12025550123", "hash", "");
    assert!(request.email.is_empty());
    let EmailVerifyPurpose::EmailVerifyPurposeLoginSetup(purpose) = *request.purpose else {
        panic!("expected login purpose")
    };
    assert_eq!(purpose.phone_code_hash, "hash");
    for (email, expected) in [
        ("", AuthCodeTypeDto::Email),
        ("a@example.com", AuthCodeTypeDto::EmailSetupCode),
    ] {
        let step = email_code_to_step(
            "+12025550123",
            "hash",
            email,
            AccountSentEmailCode::AccountSentEmailCode(AccountSentEmailCodeConstructor {
                email_pattern: "a***@example.com".into(),
                length: 6,
            }),
        );
        let AuthStepDto::AwaitingCode { sent } = step else {
            panic!("expected code")
        };
        assert_eq!(sent.code_type, expected);
        assert_eq!(sent.email_address.is_some(), !email.is_empty());
    }
}
