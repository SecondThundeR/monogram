//! https://core.telegram.org/api/auth
//! https://core.telegram.org/method/auth.sendCode

use tellers_mtproto_session::{OsRandom, Snapshot};

use crate::auth::auth_rpc;
use crate::session_file::FileSessionStore;
use crate::{AuthCodeTypeDto, AuthSignedIn, AuthStepDto, MtprotoError};

use super::*;

pub fn connect(handle: u64) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        if state.session_dead {
            // A server-revoked key must never be reused. Auth flow will create
            // a fresh key from sendCode; connect itself stays a harmless probe.
            return Ok(());
        }
        crate::rpc::set_use_test_dc(state.test_dc);
        let mut persist_needed = false;
        if !state.test_dc {
            crate::dns_txt::load_sidecar(&state.session_path);
            let home_dc = state.snapshot.dc_id;
            let cached = crate::dns_txt::apply_fresh_cache(&state.session_path, home_dc);
            ensure_ready(state)?;
            if !cached {
                crate::dns_txt::refresh_home_from_backup(
                    &state.session_path,
                    home_dc,
                    state.api_id,
                    &mut state.snapshot,
                );
                persist_needed = true;
            }
        } else {
            ensure_ready(state)?;
        }
        if state.user_id.is_some()
            && !state.test_dc
            && !crate::scheduler::main_session_allowance_known()
        {
            // One `help.getConfig` on the main session decides whether extra home-DC
            // sessions (read lanes, home-DC file lanes) are permitted at all.
            crate::dns_txt::probe_main_session_allowance(
                state.snapshot.dc_id,
                state.api_id,
                &mut state.snapshot,
            );
            persist_needed = true;
        }
        if persist_needed {
            persist(state)?;
        }
        recover_imported_user(state)?;
        Ok(())
    })?;
    crate::auth::bind_temp::prepare_extra_main_sessions(handle);
    Ok(())
}

fn recover_imported_user(state: &mut ClientState) -> Result<(), MtprotoError> {
    if state.session_dead
        || state.user_id.is_some()
        || state.snapshot.auth_key.as_ref().map(|k| k.len()) != Some(256)
    {
        return Ok(());
    }
    match crate::profile::get_profile(
        &mut state.snapshot,
        state.api_id,
        &state.peers,
        &mut state.media,
        None,
        0,
    ) {
        Ok(profile) if profile.id > 0 => {
            state.user_id = Some(profile.id);
            persist(state)
        }
        Ok(_) => Ok(()),
        Err(_) => Ok(()),
    }
}

pub fn is_authorized(handle: u64) -> Result<bool, MtprotoError> {
    with_client_mut(handle, |state| {
        Ok(!state.session_dead
            && state.user_id.is_some()
            && state.snapshot.auth_key.as_ref().map(|k| k.len()) == Some(256))
    })
}

pub(crate) fn is_auth_restart(err: &MtprotoError) -> bool {
    matches!(err, MtprotoError::Message(m) if m.contains("AUTH_RESTART"))
}

pub(crate) fn is_password_required(err: &MtprotoError) -> bool {
    matches!(err, MtprotoError::PasswordRequired)
        || matches!(err, MtprotoError::Message(m) if m.contains("SESSION_PASSWORD_NEEDED"))
}

pub(crate) fn apply_test_dc(
    state: &mut ClientState,
    want_test: bool,
    want_dc: i32,
) -> Result<(), MtprotoError> {
    if state.test_dc == want_test && (!want_test || state.snapshot.dc_id == want_dc) {
        crate::rpc::set_use_test_dc(want_test);
        return Ok(());
    }
    crate::rpc::drop_live_transport();
    crate::rpc::set_use_test_dc(want_test);
    state.test_dc = want_test;
    state.dc_auth_keys.clear();
    state.pending_auth = AuthStepDto::LoggedOut;
    state.snapshot =
        Snapshot::new(want_dc, &mut OsRandom).map_err(|e| MtprotoError::Message(e.to_string()))?;
    state.session_dead = false;
    persist(state)
}

pub(crate) fn apply_test_dc_for_phone(
    state: &mut ClientState,
    phone: &str,
) -> Result<(), MtprotoError> {
    let from_phone = auth_rpc::test_dc_from_phone(phone);
    let want_test = state.test_dc || from_phone.is_some();
    let want_dc = from_phone.unwrap_or(if want_test {
        DEFAULT_DC_ID
    } else {
        state.snapshot.dc_id
    });
    apply_test_dc(state, want_test, want_dc)
}

pub fn set_client_test_dc(handle: u64, test: bool) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| apply_test_dc(state, test, DEFAULT_DC_ID))
}

pub fn send_auth_code(handle: u64, phone: String) -> Result<AuthStepDto, MtprotoError> {
    let sent = with_client_mut(handle, |state| {
        apply_test_dc_for_phone(state, &phone)?;
        if state.session_dead {
            // account.* before sign-in returns AUTH_KEY_UNREGISTERED. The key is
            // still the one auth.sendCode must use; replacing it drops the login.
            let unregistered = state.user_id.is_none()
                && state.session_dead_reason.as_deref() == Some("AUTH_KEY_UNREGISTERED");
            if unregistered {
                state.session_dead = false;
                state.session_dead_reason = None;
            } else {
                recreate_mtproto_session(state)?;
            }
        }
        let attempt = |state: &mut ClientState| {
            call_with_migrate(state, |state| {
                auth_rpc::send_code(
                    &mut state.snapshot,
                    state.api_id,
                    &state.api_hash,
                    &phone,
                    &mut state.logout_tokens,
                )
            })
        };
        let result = match attempt(state) {
            Err(err) if is_auth_restart(&err) || auth_rpc::is_srp_id_invalid(&err) => {
                recreate_mtproto_session(state)?;
                ensure_ready(state)?;
                attempt(state)
            }
            result => result,
        };
        finish_auth_step(state, result, &phone)
    })?;
    drop_updates_transport(handle);
    Ok(sent)
}

pub fn cancel_auth(handle: u64) -> Result<(), MtprotoError> {
    with_client_mut(handle, |state| {
        if matches!(state.pending_auth, AuthStepDto::AwaitingPassword { .. }) {
            return recreate_mtproto_session(state);
        }
        state.pending_auth = AuthStepDto::LoggedOut;
        persist(state)
    })
}

pub fn current_auth_step(handle: u64) -> Result<AuthStepDto, MtprotoError> {
    with_client_mut(handle, |state| {
        if state.session_dead {
            return Ok(AuthStepDto::LoggedOut);
        }
        if let Some(user_id) = state.user_id {
            return Ok(AuthStepDto::Authorized {
                signed: AuthSignedIn {
                    user_id,
                    dc_id: state.snapshot.dc_id,
                },
            });
        }
        Ok(state.pending_auth.clone())
    })
}

fn apply_auth_step(state: &mut ClientState, step: &AuthStepDto) -> Result<(), MtprotoError> {
    if let AuthStepDto::Authorized { signed } = step {
        state.user_id = Some(signed.user_id);
        state.pending_auth = AuthStepDto::LoggedOut;
    } else {
        state.pending_auth = step.clone();
    }
    persist(state)
}

pub(super) fn finish_auth_step(
    state: &mut ClientState,
    result: Result<AuthStepDto, MtprotoError>,
    phone: &str,
) -> Result<AuthStepDto, MtprotoError> {
    let step = match result {
        Ok(step) => step,
        Err(error) if is_password_required(&error) => AuthStepDto::AwaitingPassword {
            phone: phone.into(),
        },
        Err(error) => {
            if is_auth_restart(&error) {
                recreate_mtproto_session(state)?;
            } else if let MtprotoError::Message(message) = &error {
                if message.contains("PHONE_CODE_EXPIRED") {
                    state.pending_auth = AuthStepDto::LoggedOut;
                } else if let Some(wait) = message.split("FLOOD_WAIT_").nth(1) {
                    let seconds = wait
                        .chars()
                        .take_while(char::is_ascii_digit)
                        .collect::<String>()
                        .parse::<i64>()
                        .ok();
                    if let (Some(seconds), AuthStepDto::AwaitingCode { sent }) =
                        (seconds, &mut state.pending_auth)
                    {
                        sent.resend_available_at = sent
                            .resend_available_at
                            .max(auth_rpc::now().saturating_add(seconds));
                    }
                }
            }
            persist(state)?;
            return Err(error);
        }
    };
    apply_auth_step(state, &step)?;
    Ok(step)
}

fn pending_code(
    state: &ClientState,
    phone: &str,
    hash: &str,
) -> Result<crate::AuthCodeSent, MtprotoError> {
    match &state.pending_auth {
        AuthStepDto::AwaitingCode { sent }
            if sent.phone == phone && sent.phone_code_hash == hash =>
        {
            Ok(sent.clone())
        }
        _ => Err(MtprotoError::Message("RPC 400: PHONE_CODE_EXPIRED".into())),
    }
}

pub fn resend_auth_code(
    handle: u64,
    phone: String,
    phone_code_hash: String,
) -> Result<AuthStepDto, MtprotoError> {
    let result = with_client_mut(handle, |state| {
        let pending = pending_code(state, &phone, &phone_code_hash)?;
        let delay = pending.resend_available_at - auth_rpc::now();
        if delay > 0 {
            return Err(MtprotoError::Message(format!(
                "RPC 420: FLOOD_WAIT_{delay}"
            )));
        }
        let step = if matches!(
            pending.code_type,
            AuthCodeTypeDto::Email | AuthCodeTypeDto::EmailSetupCode
        ) {
            let email = pending.email_address.as_deref().unwrap_or_default();
            let mut step = call_with_migrate(state, |state| {
                auth_rpc::send_email(
                    &mut state.snapshot,
                    state.api_id,
                    &phone,
                    &phone_code_hash,
                    email,
                )
            });
            if let Ok(AuthStepDto::AwaitingCode { sent }) = &mut step {
                sent.email_reset_available_at = pending.email_reset_available_at;
                sent.email_reset_pending_date = pending.email_reset_pending_date;
                sent.google_signin_allowed = pending.google_signin_allowed;
                sent.apple_signin_allowed = pending.apple_signin_allowed;
            }
            step
        } else {
            if pending.next_type.is_none() {
                return Err(MtprotoError::Message(
                    "RPC 400: SEND_CODE_UNAVAILABLE".into(),
                ));
            }
            call_with_migrate(state, |state| {
                auth_rpc::resend_code(
                    &mut state.snapshot,
                    state.api_id,
                    &phone,
                    &phone_code_hash,
                    &mut state.logout_tokens,
                )
            })
        };
        finish_auth_step(state, step, &phone)
    })?;
    drop_updates_transport(handle);
    Ok(result)
}

pub fn send_auth_email(
    handle: u64,
    phone: String,
    phone_code_hash: String,
    email: String,
) -> Result<AuthStepDto, MtprotoError> {
    with_client_mut(handle, |state| {
        let pending = pending_code(state, &phone, &phone_code_hash)?;
        if !matches!(
            pending.code_type,
            AuthCodeTypeDto::EmailSetup | AuthCodeTypeDto::EmailSetupCode
        ) {
            return Err(MtprotoError::Message("RPC 400: EMAIL_NOT_ALLOWED".into()));
        }
        let step = call_with_migrate(state, |state| {
            auth_rpc::send_email(
                &mut state.snapshot,
                state.api_id,
                &phone,
                &phone_code_hash,
                &email,
            )
        });
        finish_auth_step(state, step, &phone)
    })
}

pub fn reset_auth_email(
    handle: u64,
    phone: String,
    phone_code_hash: String,
) -> Result<AuthStepDto, MtprotoError> {
    with_client_mut(handle, |state| {
        let pending = pending_code(state, &phone, &phone_code_hash)?;
        if pending.code_type != AuthCodeTypeDto::Email {
            return Err(MtprotoError::Message("RPC 400: EMAIL_NOT_ALLOWED".into()));
        }
        let deadline = pending.email_reset_pending_date.or(pending.email_reset_available_at)
            .ok_or_else(|| MtprotoError::Message("RPC 400: EMAIL_NOT_ALLOWED".into()))?;
        let delay = deadline - auth_rpc::now();
        if delay > 0 {
            return Err(MtprotoError::Message(format!("RPC 420: FLOOD_WAIT_{delay}")));
        }
        let step = call_with_migrate(state, |state| {
            auth_rpc::reset_email(
                &mut state.snapshot,
                state.api_id,
                &phone,
                &phone_code_hash,
                &mut state.logout_tokens,
            )
        });
        finish_auth_step(state, step, &phone)
    })
}

pub fn sign_in(
    handle: u64,
    phone: String,
    phone_code_hash: String,
    phone_code: String,
) -> Result<AuthStepDto, MtprotoError> {
    let step = with_client_mut(handle, |state| {
        let pending = pending_code(state, &phone, &phone_code_hash)?;
        let result = call_with_migrate(state, |state| {
            if pending.code_type == AuthCodeTypeDto::EmailSetupCode {
                auth_rpc::verify_email(
                    &mut state.snapshot,
                    state.api_id,
                    &phone,
                    &phone_code_hash,
                    &phone_code,
                    &mut state.logout_tokens,
                )
            } else {
                auth_rpc::sign_in(
                    &mut state.snapshot,
                    state.api_id,
                    &phone,
                    &phone_code_hash,
                    &phone_code,
                    pending.code_type == AuthCodeTypeDto::Email,
                    &mut state.logout_tokens,
                )
                .map(|signed| AuthStepDto::Authorized { signed })
            }
        });
        finish_auth_step(state, result, &phone)
    })?;
    drop_updates_transport(handle);
    Ok(step)
}

pub fn check_password(handle: u64, password: String) -> Result<AuthSignedIn, MtprotoError> {
    let signed = with_client_mut(handle, |state| {
        let signed = match call_with_migrate(state, |state| {
            auth_rpc::check_password(
                &mut state.snapshot,
                state.api_id,
                &password,
                &mut state.logout_tokens,
            )
        }) {
            Ok(signed) => signed,
            Err(err) if is_auth_restart(&err) => {
                // Surface restart to UI; session is reset so the next sendCode is clean.
                recreate_mtproto_session(state)?;
                return Err(err);
            }
            Err(err) => return Err(err),
        };
        state.user_id = Some(signed.user_id);
        state.pending_auth = AuthStepDto::LoggedOut;
        persist(state)?;
        Ok(signed)
    })?;
    drop_updates_transport(handle);
    Ok(signed)
}

/// Revoke the server-side authorization when possible, then always tear down
/// the local session. A remote failure is returned to the bridge after the
/// local snapshot and all transports have been cleared.
pub fn logout(handle: u64) -> Result<(), MtprotoError> {
    let result = with_client_mut(handle, |state| {
        let remote = if state.user_id.is_some()
            && !state.session_dead
            && state.snapshot.auth_key.as_ref().map(|key| key.len()) == Some(256)
        {
            auth_rpc::log_out(&mut state.snapshot, state.api_id)
        } else {
            Ok(None)
        };

        let dc = state.snapshot.dc_id;
        if let Ok(token) = &remote {
            auth_rpc::remember_token(&mut state.logout_tokens, token.clone());
        }
        state.user_id = None;
        state.pending_auth = AuthStepDto::LoggedOut;
        state.peers.clear();
        state.updates = None;
        state.media.clear();
        state.channel_pts.clear();
        state.channel_recovery.clear();
        state.seen_messages.clear();
        std::sync::Arc::make_mut(&mut state.journal).reset_account();
        state.updates_started = false;
        state.session_dead = false;
        state.new_session = None;
        state.dc_auth_keys.clear();
        state.snapshot =
            Snapshot::new(dc, &mut OsRandom).map_err(|e| MtprotoError::Message(e.to_string()))?;
        if state.logout_tokens.is_empty() {
            FileSessionStore::new(&state.session_path)
                .clear()
                .map_err(|e| MtprotoError::Message(e.to_string()))?;
        } else {
            persist(state)?;
        }

        remote.map(|_| ())
    });
    if let Ok(client) = get_client(handle) {
        client.data.lock().dc_temp_auth.clear();
    }
    drop_all_transports(handle);
    result
}
