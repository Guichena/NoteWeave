update user_session
set session_token = ''
where session_token <> '';
