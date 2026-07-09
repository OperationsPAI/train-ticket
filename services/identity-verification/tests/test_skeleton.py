from identity_verification import create_app, health, profile


def test_profile():
    assert health() == "ok"
    assert profile()["service_id"] == "identity-verification"
    assert create_app()
