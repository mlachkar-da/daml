-- Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

{-# LANGUAGE UndecidableInstances #-}

module DA.Daml.Assistant.Types
    ( module DA.Daml.Assistant.Types
    , module DA.Daml.Project.Types
    , YesNoAuto (..)
    , Text, pack, unpack -- convenient re-exports
    ) where

import DA.Daml.Project.Types
import qualified Data.Text as T
import Data.Text (Text, pack, unpack)
import Data.Maybe
import Network.HTTP.Types.Header
import Options.Applicative.Extended (YesNoAuto (..))
import Control.Exception.Safe
import Data.Functor.Identity

data AssistantError = AssistantError
    { errContext  :: Maybe Text -- ^ Context in which error occurs.
    , errMessage  :: Maybe Text -- ^ User-friendly error message.
    , errInternal :: Maybe Text -- ^ Internal error message, i.e. what actually happened.
    } deriving (Eq, Show)

instance Exception AssistantError where
    displayException AssistantError {..} = unpack . T.unlines . catMaybes $
        [ Just ("daml: " <> fromMaybe "An unknown error has occured" errMessage)
        , fmap ("  context: " <>) errContext
        , fmap ("  details: " <>) errInternal
        ]

-- | Standard error message.
assistantError :: Text -> AssistantError
assistantError msg = AssistantError
    { errContext = Nothing
    , errMessage = Just msg
    , errInternal = Nothing
    }

-- | Standard error message with additional internal cause.
assistantErrorBecause ::  Text -> Text -> AssistantError
assistantErrorBecause msg e = (assistantError msg) { errInternal = Just e }

-- | Standard error message with additional details.
assistantErrorDetails :: String -> [(String, String)] -> AssistantError
assistantErrorDetails msg details =
    assistantErrorBecause (pack msg) . pack . concat $
        ["\n    " <> k <> ": " <> v | (k,v) <- details]

data EnvF f = Env
    { envDamlPath      :: DamlPath
    , envCachePath :: CachePath
    , envDamlAssistantPath :: DamlAssistantPath
    , envDamlAssistantSdkVersion :: Maybe DamlAssistantSdkVersion
    , envProjectPath   :: Maybe ProjectPath
    , envSdkPath       :: Maybe SdkPath
    , envSdkVersion    :: Maybe SdkVersion
    , envFreshStableSdkVersionForCheck :: f (Maybe SdkVersion)
    }

deriving instance Eq (f (Maybe SdkVersion)) => Eq (EnvF f)
deriving instance Show (f (Maybe SdkVersion)) => Show (EnvF f)

type Env = EnvF IO

forceEnv :: Monad m => EnvF m -> m (EnvF Identity)
forceEnv Env{..} = do
  envFreshStableSdkVersionForCheck <- fmap Identity envFreshStableSdkVersionForCheck
  pure Env{..}

data BuiltinCommand
    = Version VersionOptions
    | Exec String [String]
    | Install InstallOptions
    | Uninstall SdkVersion
    deriving (Eq, Show)

newtype LookForProjectPath = LookForProjectPath
    { unLookForProjectPath :: Bool }

data Command
    = Builtin BuiltinCommand
    | Dispatch SdkCommandInfo UserCommandArgs
    deriving (Eq, Show)

newtype UserCommandArgs = UserCommandArgs
    { unwrapUserCommandArgs :: [String]
    } deriving (Eq, Show)

-- | Command-line options for daml version command.
data VersionOptions = VersionOptions
    { vAll :: Bool -- ^ show all versions (stable + snapshot)
    , vSnapshots :: Bool -- ^ show all snapshot versions
    , vAssistant :: Bool -- ^ show assistant version
    , vForceRefresh :: Bool -- ^ force refresh available versions, don't use 1-day cache
    } deriving (Eq, Show)

-- | Command-line options for daml install command.
data InstallOptions = InstallOptions
    { iTargetM :: Maybe RawInstallTarget -- ^ version to install
    , iSnapshots :: Bool -- ^ include snapshots for latest target
    , iAssistant :: InstallAssistant -- ^ install the assistant
    , iActivate :: ActivateInstall -- ^ install the assistant if true (deprecated, delete with 0.14.x)
    , iForce :: ForceInstall -- ^ force reinstall if already installed
    , iQuiet :: QuietInstall -- ^ don't print messages
    , iSetPath :: SetPath -- ^ set the user's PATH (on Windows)
    , iBashCompletions :: BashCompletions -- ^ install bash completions for the daml assistant
    , iZshCompletions :: ZshCompletions -- ^ install Zsh completions for the daml assistant
    } deriving (Eq, Show)

-- | An install locations is a pair of fully qualified HTTP[S] URL to an SDK release tarball and headers
-- required to access that URL. For example:
-- "https://github.com/digital-asset/daml/releases/download/v0.11.1/daml-sdk-0.11.1-macos.tar.gz"
data InstallLocation = InstallLocation
    { ilUrl :: Text
    , ilHeaders :: RequestHeaders
    } deriving (Eq, Show)

newtype RawInstallTarget = RawInstallTarget String deriving (Eq, Show)
newtype ForceInstall = ForceInstall { unForceInstall :: Bool } deriving (Eq, Show)
newtype QuietInstall = QuietInstall { unQuietInstall :: Bool } deriving (Eq, Show)
newtype ActivateInstall = ActivateInstall { unActivateInstall :: Bool } deriving (Eq, Show)
newtype SetPath = SetPath {unwrapSetPath :: YesNoAuto} deriving (Eq, Show)
newtype InstallAssistant = InstallAssistant { unwrapInstallAssistant :: YesNoAuto } deriving (Eq, Show)
newtype BashCompletions = BashCompletions { unwrapBashCompletions :: YesNoAuto } deriving (Eq, Show)
newtype ZshCompletions = ZshCompletions { unwrapZshCompletions :: YesNoAuto } deriving (Eq, Show)
