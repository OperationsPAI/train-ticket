from pathlib import Path
from setuptools import setup

ROOT = Path(__file__).resolve().parents[2]
KIT = ROOT / "platform" / "python-kit"
setup(
    install_requires=[
        f"train-ticket-platform @ file://{KIT}",
        "fastapi==0.138.1",
        "redis>=5.0.0",
        "pydantic>=2.0.0",
    ],
)
