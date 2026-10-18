import { Navigate, Route, Routes } from 'react-router-dom';
import { Layout } from './components/Layout';
import { GamesPage } from './pages/GamesPage';
import { GameRoomPage } from './pages/GameRoomPage';
import { LoginPage } from './pages/LoginPage';
import { ProfilePage } from './pages/ProfilePage';
import { RegisterPage } from './pages/RegisterPage';
import { RequireAuth } from './routes/RequireAuth';

export function App() {
  return (
    <Layout>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route
          path="/games"
          element={
            <RequireAuth>
              <GamesPage />
            </RequireAuth>
          }
        />
        <Route
          path="/games/:gameId"
          element={
            <RequireAuth>
              <GameRoomPage />
            </RequireAuth>
          }
        />
        <Route
          path="/profile"
          element={
            <RequireAuth>
              <ProfilePage />
            </RequireAuth>
          }
        />
        <Route path="/" element={<Navigate to="/games" replace />} />
        <Route path="*" element={<Navigate to="/games" replace />} />
      </Routes>
    </Layout>
  );
}
